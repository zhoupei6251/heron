package org.dromara.heron.agentscope;

import cn.hutool.core.util.IdUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DirectorRepairAgent {

    private static final String STAGE = "visual-rework-plan";
    private final ObjectProvider<Model> models;
    private final ProductionUsageService usage;

    public DirectorRepairAgent(ObjectProvider<Model> models, ProductionUsageService usage) {
        this.models = models;
        this.usage = usage;
    }

    public boolean enabled() {
        return models.getIfUnique() != null;
    }

    public OptionalInt choose(ProductionRunVo run, String candidates, int count) {
        Model model = models.getIfUnique();
        String callId = IdUtil.getSnowflakeNextIdStr();
        if (model == null || !usage.start(run, STAGE, callId, "agentscope", model.getModelName())) {
            return OptionalInt.empty();
        }
        long start = System.nanoTime();
        AtomicLong input = new AtomicLong();
        AtomicLong output = new AtomicLong();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RecipeTool());
        String session = "director-rework-" + run.taskId() + "-" + run.attemptNo();
        try (ReActAgent agent = ReActAgent.builder().name("heron-director-repair").defaultSessionId(session)
            .sysPrompt("你是视觉返工编辑。只能选择候选清单中一个方案。先查配方适用条件，优先修复开场、可读性和遮挡；不要改文案、数据或时序。最后只输出一个从 1 开始的候选序号。")
            .model(model).toolkit(toolkit).maxIters(2).hook(new Hook() {
                @Override
                public <T extends HookEvent> Mono<T> onEvent(T event) {
                    if (event instanceof PostReasoningEvent reasoning && reasoning.getReasoningMessage() != null) {
                        var measured = reasoning.getReasoningMessage().getChatUsage();
                        if (measured != null) {
                            input.addAndGet(measured.getInputTokens());
                            output.addAndGet(measured.getOutputTokens());
                        }
                    }
                    return Mono.just(event);
                }
            }).build()) {
            var reply = agent.call(candidates).block(Duration.ofSeconds(45));
            String answer = reply == null ? "" : reply.getTextContent().strip();
            if (!answer.matches("[1-3]") || Integer.parseInt(answer) > count) {
                usage.fail(run, callId, "visual_rework_plan_invalid", elapsed(start));
                return OptionalInt.empty();
            }
            usage.complete(run, callId, null, input.get() > 0 ? input.get() : null,
                output.get() > 0 ? output.get() : null, elapsed(start));
            return OptionalInt.of(Integer.parseInt(answer) - 1);
        } catch (RuntimeException e) {
            usage.fail(run, callId, "visual_rework_plan_failed", elapsed(start));
            return OptionalInt.empty();
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    public static final class RecipeTool {
        @Tool(name = "read_repair_recipes", description = "读取三种已实现的定向修图配方，不执行渲染", readOnly = true)
        public String readRepairRecipes() {
            return "readability：提升小字字号和对比，适合数字单位或副文；hierarchy：压低装饰强调，突出一个主体；spacing：收紧装饰与空隙，为主标题和图表释放空间。都不改字幕、数值、素材和时序。";
        }
    }
}
