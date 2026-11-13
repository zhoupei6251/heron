package org.dromara.heron.agentscope;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    private final Model model;
    private final HeronResearchTools tools;
    private final AgentStateStore stateStore;
    private final int maxIters;

    public ResearchAgentFactory(Model model, HeronResearchTools tools, AgentStateStore stateStore, int maxIters) {
        this.model = model;
        this.tools = tools;
        this.stateStore = stateStore;
        this.maxIters = maxIters;
    }

    /** 轮数上限：撞上它等于「跑满了还没结论」，要标 partial。 */
    public int maxIters() {
        return maxIters;
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
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话标识不能为空，否则所有研究共用一段上下文");
        }
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(budget == null ? tools : tools.withBudget(budget));

        HarnessAgent.Builder builder = HarnessAgent.builder()
            .name(AGENT_NAME)
            .defaultSessionId(sessionId)
            .sysPrompt(resource(SYSTEM_PROMPT))
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
