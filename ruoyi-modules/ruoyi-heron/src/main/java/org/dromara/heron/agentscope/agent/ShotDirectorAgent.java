package org.dromara.heron.agentscope.agent;

import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.model.Model;
import org.dromara.heron.agentscope.research.ResearchBudget;
import org.dromara.heron.agentscope.toolkit.ShotRenderTools;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.shared.production.ProductionUsageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 镜头导演：每镜一个受控 ReAct 会话。
 *
 * <p>失败不抛回主链——返回 false，调用方回退确定性画面。
 * 同镜返工复用同一 sessionId（taskId+attempt+shotId），跨轮上下文由会话承载。
 */
@Slf4j
@Service
public class ShotDirectorAgent {

    private static final String STAGE = "shot-director";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final ObjectProvider<Model> models;
    private final ProductionUsageService usage;

    public ShotDirectorAgent(ObjectProvider<Model> models, ProductionUsageService usage) {
        this.models = models;
        this.usage = usage;
    }

    /** @return true 表示模型 Bean 唯一可用、可以发起镜头导演会话；false 时调用方跳过 Agent 镜头 */
    public boolean enabled() {
        return models.getIfUnique() != null;
    }

    /**
     * 让 Agent 为单镜写 HTML；失败返回 false，不把异常抛回主链。
     *
     * <p>成功与否以文件落地为准：跑完后 {@code shotHtml} 必须是非空普通文件，
     * 模型口头说写完不算。用量同时累加到 {@code budget}（可为 null）。
     *
     * @param run      当前生产运行，用于用量记账
     * @param tools    该镜头的受控工具面
     * @param goal     本轮目标（首轮任务卡或返工说明）
     * @param shotHtml 目标 HTML 路径，跑完后按它判断是否写成功
     * @param budget   可选预算，用于累计 token 并拒绝超限的工具调用
     * @return true 表示 HTML 已写入且非空；模型不可用、记账未开始、调用失败、文件未写或不可读时为 false，
     *         调用方保留确定性画面
     */
    public boolean direct(ProductionRunVo run, ShotRenderTools tools, String goal, Path shotHtml,
                          org.dromara.heron.agentscope.research.ResearchBudget budget) {
        Model model = models.getIfUnique();
        if (model == null) {
            return false;
        }
        String callId = IdUtil.getSnowflakeNextIdStr();
        if (!usage.start(run, STAGE, callId, "agentscope", model.getModelName())) {
            return false;
        }
        long start = System.nanoTime();
        AtomicLong input = new AtomicLong();
        AtomicLong output = new AtomicLong();
        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerTool(tools);
        String session = "shot-" + run.taskId() + "-" + run.attemptNo() + "-" + tools.shotId();
        try (ReActAgent agent = ReActAgent.builder()
            .name("heron-shot-director")
            .defaultSessionId(session)
            .sysPrompt(resource())
            .model(model)
            .toolkit(toolkit)
            .maxIters(4)
            .hook(new Hook() {
                @Override
                public <T extends HookEvent> Mono<T> onEvent(T event) {
                    if (event instanceof PostReasoningEvent reasoning && reasoning.getReasoningMessage() != null) {
                        var measured = reasoning.getReasoningMessage().getChatUsage();
                        if (measured != null) {
                            input.addAndGet(measured.getInputTokens());
                            output.addAndGet(measured.getOutputTokens());
                            if (budget != null) {
                                budget.addUsage(measured.getInputTokens(), measured.getOutputTokens());
                            }
                        }
                    }
                    return Mono.just(event);
                }
            })
            .build()) {
            agent.call(goal).block(TIMEOUT);
            boolean written = Files.isRegularFile(shotHtml) && Files.size(shotHtml) > 0;
            if (!written) {
                log.warn("镜头导演未写回镜头文件，保留确定性画面 shot={} taskId={} attempt={}",
                    tools.shotId(), run.taskId(), run.attemptNo());
                usage.fail(run, callId, "shot_not_written", elapsed(start));
                return false;
            }
            usage.complete(run, callId, null, input.get() > 0 ? input.get() : null,
                output.get() > 0 ? output.get() : null, elapsed(start));
            return true;
        } catch (RuntimeException e) {
            log.warn("镜头导演执行失败，保留确定性画面 shot={} taskId={} attempt={}",
                tools.shotId(), run.taskId(), run.attemptNo(), e);
            usage.fail(run, callId, "shot_director_failed", elapsed(start));
            return false;
        } catch (IOException e) {
            log.warn("镜头文件不可读，保留确定性画面 shot={} taskId={} attempt={}",
                tools.shotId(), run.taskId(), run.attemptNo(), e);
            usage.fail(run, callId, "shot_write_unreadable", elapsed(start));
            return false;
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /** 读取镜头导演提示词；缺失或不可读直接抛 {@link IllegalStateException}——这是配置错误，不降级。 */
    private static String resource() {
        try {
            return new ClassPathResource("/prompt/shot-director/shot-director.md")
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("镜头导演提示词不可读", e);
        }
    }
}
