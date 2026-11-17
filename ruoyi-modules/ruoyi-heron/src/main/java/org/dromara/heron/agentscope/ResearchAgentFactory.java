package org.dromara.heron.agentscope;

import cn.hutool.crypto.digest.DigestUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.harness.agent.HarnessAgent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 按会话创建研究 Agent。
 *
 * <p>为什么不是单例：AgentState 的键是「agentId + sessionId」，`HarnessAgent` 没有带上下文的
 * {@code call} 重载，会话只能在构建时用 {@code defaultSessionId} 定死。
 * 一个单例就意味着所有研究共用一段上下文——测试夹具和上一次的结论会被当成真实历史读回来，
 * 这个事故已经发生过一次。
 *
 * <p>名字固定、会话变化：隔离靠 sessionId；同一个 sessionId 再次进入则是有意的续跑。
 */
public class ResearchAgentFactory {

    static final String AGENT_NAME = "heron-research";
    private static final String SYSTEM_PROMPT = "/prompt/research/researcher.md";
    /** 核验员是另一个 Agent：独立会话、独立提示词，看不到研究员的过程，只看数据本身。 */
    static final String VERIFIER_NAME = "heron-verifier";
    private static final String VERIFIER_PROMPT = "/prompt/research/verifier.md";

    private final Model model;
    private final HeronResearchTools tools;
    private final AgentStateStore stateStore;
    private final int maxIters;
    private final Duration toolTimeout;

    public ResearchAgentFactory(Model model, HeronResearchTools tools, AgentStateStore stateStore,
                                int maxIters, Duration toolTimeout) {
        this.model = model;
        this.tools = tools;
        this.stateStore = stateStore;
        this.maxIters = maxIters;
        this.toolTimeout = toolTimeout;
    }

    /** 轮数上限：撞上它等于「跑满了还没结论」，要标 partial。 */
    public int maxIters() {
        return maxIters;
    }

    /** 模型名进报告：从实例本身取，不再读一遍配置，免得两处不一致。 */
    public String modelName() {
        String name = model.getModelName();
        return name == null ? "unknown" : name;
    }

    /**
     * 提示词版本：内容摘要前 12 位。
     *
     * <p>用摘要而不是手写版本号：改了提示词却忘了改版本号，是这个领域最常见的谎报。
     */
    public String promptVersion() {
        return DigestUtil.sha256Hex(resource(SYSTEM_PROMPT)).substring(0, 12);
    }

    /** 核验员的提示词版本，单独算：改核验口径不该影响研究提示词的版本号。 */
    public String verifierPromptVersion() {
        return DigestUtil.sha256Hex(resource(VERIFIER_PROMPT)).substring(0, 12);
    }

    /** 不带账本的简用入口。 */
    public HarnessAgent create(String sessionId) {
        return create(sessionId, null, null);
    }

    public HarnessAgent create(String sessionId, Hook hook) {
        return create(sessionId, null, hook);
    }

    /**
     * @param sessionId 研究级会话标识；同一个研究重试时应传同一个值
     * @param hook      可选的观测/预算钩子（工具调用账本挂在这里）
     */
    public HarnessAgent create(String sessionId, ResearchBudget budget, Hook hook) {
        return create(sessionId, budget, hook, new ResearchFindings());
    }

    /**
     * @param findings 模型主动收尾时写入的结论；每次研究一份，不共享
     */
    public HarnessAgent create(String sessionId, ResearchBudget budget, Hook hook,
                               ResearchFindings findings) {
        return create(sessionId, budget, hook, findings, null);
    }

    /**
     * @param snapshots 本次 attempt 的数据快照冻结；为 null 表示不落库
     */
    public HarnessAgent create(String sessionId, ResearchBudget budget, Hook hook,
                               ResearchFindings findings, ResearchSnapshots snapshots) {
        checkSession(sessionId);
        return build(sessionId, AGENT_NAME, SYSTEM_PROMPT,
            scoped(budget, findings, snapshots), hook);
    }

    /**
     * 建核验员：同一批查询工具，换成核验的提示词和收尾工具。
     *
     * <p>会话标识要和研究区分开（调用方传 {@code verify-<researchId>-<attempt>}）：
     * 共用一段上下文就等于让它「记得」研究员是怎么推的，那还叫独立复核吗。
     */
    public HarnessAgent createVerifier(String sessionId, ResearchBudget budget, Hook hook,
                                       ResearchVerification verification) {
        checkSession(sessionId);
        return build(sessionId, VERIFIER_NAME, VERIFIER_PROMPT,
            budget == null ? tools : tools.withBudget(budget),
            hook, new ResearchVerificationTool(verification));
    }

    /**
     * 建 L2 执行器：裸 ReActAgent。
     *
     * <p>为什么不用 HarnessAgent：plan §12 的口径就是 L2=ReActAgent、L3=HarnessAgent。
     * Harness 额外带的是计划模式、任务清单、子 Agent 那一整套——中档机会用不上，
     * 带着它们反而多一层可能出问题的地方。
     */
    public ReActAgent createReAct(String sessionId, ResearchBudget budget, Hook hook,
                                  ResearchFindings findings) {
        return createReAct(sessionId, budget, hook, findings, null);
    }

    public ReActAgent createReAct(String sessionId, ResearchBudget budget, Hook hook,
                                  ResearchFindings findings, ResearchSnapshots snapshots) {
        checkSession(sessionId);
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
            .executionConfig(ExecutionConfig.builder()
                .timeout(toolTimeout)
                .maxAttempts(1)
                .build())
            .build());
        toolkit.registerTool(scoped(budget, findings, snapshots));

        ReActAgent.Builder builder = ReActAgent.builder()
            .name(AGENT_NAME + "-react")
            .sysPrompt(resource(SYSTEM_PROMPT))
            .model(model)
            .toolkit(toolkit)
            .maxIters(maxIters)
            .stateStore(stateStore);
        if (hook != null) {
            builder.hook(hook);
        }
        return builder.build();
    }

    /** 预算、结论容器、快照冻结都是 per-attempt 的，一律走副本，不共享。 */
    private HeronResearchTools scoped(ResearchBudget budget, ResearchFindings findings,
                                      ResearchSnapshots snapshots) {
        HeronResearchTools copy = budget == null ? tools : tools.withBudget(budget);
        if (findings != null) {
            copy = copy.withFindings(findings);
        }
        if (snapshots != null) {
            copy = copy.withSnapshots(snapshots);
        }
        return copy;
    }

    private static void checkSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话标识不能为空，否则所有研究共用一段上下文");
        }
    }

    private HarnessAgent build(String sessionId, String name, String prompt, Object toolObject,
                               Hook hook, Object... extraTools) {
        // 单工具超时由 Toolkit 统一给：框架默认是 5 分钟，一个卡住的取数会吃掉整个调查窗口
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
            .executionConfig(ExecutionConfig.builder()
                .timeout(toolTimeout)
                .maxAttempts(1)
                .build())
            .build());
        toolkit.registerTool(toolObject);
        for (Object extra : extraTools) {
            toolkit.registerTool(extra);
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
            .name(name)
            .defaultSessionId(sessionId)
            .sysPrompt(resource(prompt))
            .model(model)
            .toolkit(toolkit)
            .maxIters(maxIters)
            .stateStore(stateStore)
            .disableShellTool()
            .disableFilesystemTools()
            .disableWebTools()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableSessionPersistence()
            // 真正挡住会话落盘的是这个：disableSessionPersistence 只管状态库，不管 transcript
            .disableTranscript()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .disableSubagents()
            .disableToolsConfig();
        if (hook != null) {
            builder.hook(hook);
        }
        return builder.build();
    }

    private static String resource(String path) {
        try (InputStream stream = ResearchAgentFactory.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
