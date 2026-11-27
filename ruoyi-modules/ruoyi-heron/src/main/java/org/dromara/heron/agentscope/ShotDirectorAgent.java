package org.dromara.heron.agentscope;

import cn.hutool.core.util.IdUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.model.Model;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionUsageService;
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

    public boolean enabled() {
        return models.getIfUnique() != null;
    }

    public boolean direct(ProductionRunVo run, ShotRenderTools tools, String goal, Path shotHtml,
                          org.dromara.heron.agentscope.ResearchBudget budget) {
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
                usage.fail(run, callId, "shot_not_written", elapsed(start));
                return false;
            }
            usage.complete(run, callId, null, input.get() > 0 ? input.get() : null,
                output.get() > 0 ? output.get() : null, elapsed(start));
            return true;
        } catch (RuntimeException e) {
            usage.fail(run, callId, "shot_director_failed", elapsed(start));
            return false;
        } catch (IOException e) {
            usage.fail(run, callId, "shot_write_unreadable", elapsed(start));
            return false;
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static String resource() {
        try {
            return new ClassPathResource("/prompt/shot-director/shot-director.md")
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("镜头导演提示词不可读", e);
        }
    }
}
