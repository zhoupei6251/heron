package org.dromara.heron.service.impl;

import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronJevShadow;
import org.dromara.heron.mapper.HeronJevShadowMapper;
import org.dromara.heron.service.impl.EditorialDecisionService.Decision;
import org.dromara.heron.service.impl.EditorialDecisionService.Pools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * JEV SHADOW 对照：总编辑判完之后，把同一份候选概况再交给外部判断器判一次，只记差异。
 *
 * <p>三条硬约定：
 * <ol>
 *   <li>不进任何 if——记录只落库，方法返回值与主链分支同不接 JEV 时完全一致；</li>
 *   <li>失败、超时、预算耗尽一律记 {@code undetermined}，永远不当 PASS；</li>
 *   <li>异步执行，不阻塞判定所在的线程。</li>
 * </ol>
 *
 * <p>状态用的就是总编辑看到的那份 {@code prompt(pools)}：同一个输入交给两个判断器，
 * 差异才能归因到判断而非信息差。
 */
@Slf4j
@Service
public class JevShadowRecorder {

    /** 与总编辑的选线闭集一致：外部判断只能在这五个里选，不做开卷。 */
    private static final Map<String, Object> LINES = Map.of(
        EditorialDecisionService.NO_PUBLISH, "今天不发",
        EditorialDecisionService.DATA_BOARD, "数据看板：讲发生了什么",
        EditorialDecisionService.TREND_INSIGHT, "趋势洞察：讲变化意味着什么",
        EditorialDecisionService.NEWS_EXPLAINER, "文稿解说：有正文的文章",
        EditorialDecisionService.KNOWLEDGE_EXPLAINER, "知识解释：其余三条线都没素材时的备用线");

    private static final String ANSWER_KEY = "line";
    private static final int ERROR_CODE_LIMIT = 64;

    private final ObjectProvider<JevClient> clientProvider;
    private final HeronJevShadowMapper shadowMapper;
    private final ScheduledExecutorService executor;
    private final Environment environment;
    private final Clock clock;

    /** 日预算：只防跑飞，不是成本模型。多副本时各算各的，因此上限按单实例设。 */
    private LocalDate budgetDay;
    private int budgetUsed;

    public JevShadowRecorder(ObjectProvider<JevClient> clientProvider, HeronJevShadowMapper shadowMapper,
                             ScheduledExecutorService executor, Environment environment,
                             ObjectProvider<Clock> clocks) {
        this.clientProvider = clientProvider;
        this.shadowMapper = shadowMapper;
        this.executor = executor;
        this.environment = environment;
        this.clock = clocks.getIfAvailable(Clock::systemDefaultZone);
    }

    /**
     * 记一次对照。没配 JEV 时直接返回，连线程都不起——默认关闭必须是真的零动作。
     */
    public void record(Pools pools, Decision decision) {
        JevClient client = clientProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        String localLine = decision.publish()
            ? decision.selectedLine()
            : EditorialDecisionService.NO_PUBLISH;
        executor.submit(() -> run(client, pools, localLine));
    }

    private void run(JevClient client, Pools pools, String localLine) {
        HeronJevShadow row = new HeronJevShadow();
        row.setPoolId(pools.poolId());
        row.setPoolHash(pools.poolHash());
        row.setLocalLine(localLine);
        if (!withinBudget()) {
            row.setStatus(HeronJevShadow.STATUS_UNDETERMINED);
            row.setErrorCode("daily_budget");
            save(row);
            return;
        }
        long start = System.nanoTime();
        try {
            SystemOneResult result = client.systemOneBlocking(request(pools));
            ChoiceAnswer answer = (ChoiceAnswer) result.answers().get(ANSWER_KEY);
            row.setModel(result.model());
            row.setJevLine(answer.choice());
            row.setJevConfidence(answer.confidence());
            row.setAgree(Objects.equals(answer.choice(), localLine));
            row.setStatus(HeronJevShadow.STATUS_OK);
        } catch (RuntimeException e) {
            // 超时、非法响应、鉴权失败都走这里：记未定，不当作同意
            row.setStatus(HeronJevShadow.STATUS_UNDETERMINED);
            row.setErrorCode(errorCode(e));
            log.warn("JEV shadow 调用失败：{}", e.toString());
        } finally {
            row.setLatencyMs((System.nanoTime() - start) / 1_000_000L);
        }
        save(row);
    }

    private static SystemOneRequest request(Pools pools) {
        return SystemOneRequest.builder()
            .state(EditorialDecisionService.prompt(pools))
            .question(ANSWER_KEY, new ChoiceQuestion("今天这批素材该走哪条线？不做也是一种选择。", LINES))
            .build();
    }

    private synchronized boolean withinBudget() {
        int limit = environment.getProperty("heron.jev.daily-call-limit", Integer.class, 20);
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            budgetUsed = 0;
        }
        return ++budgetUsed <= limit;
    }

    /** 写库失败也不能把异常抛回主链：这条记录的价值远低于一次正常产出。 */
    private void save(HeronJevShadow row) {
        LocalDateTime now = LocalDateTime.now(clock);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            shadowMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("JEV shadow 记录落库失败：{}", e.toString());
        }
    }

    private static String errorCode(RuntimeException e) {
        String name = e.getClass().getSimpleName();
        return name.length() <= ERROR_CODE_LIMIT ? name : name.substring(0, ERROR_CODE_LIMIT);
    }
}
