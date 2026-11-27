package org.dromara.heron.service.impl.shared.research;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.agentscope.research.ResearchGrade;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.bo.ResearchTaskQueryBo;
import org.dromara.heron.domain.vo.ResearchTaskVo;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.IContentDecisionService;
import org.dromara.heron.service.IResearchAgent;
import org.dromara.heron.service.IResearchTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 研究任务：建任务（同机会同日只建一次）与跑一次会话。
 *
 * <p>执行用「条件更新领取」而不是先查后改：只有仍处于 queued（或租约已过期的 running）的那一方
 * 能把状态改成 running，并发触发时后到者拿到 0 行，直接返回当前状态，不会重复烧模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "heron.agent", name = "enabled", havingValue = "true")
public class ResearchTaskServiceImpl implements IResearchTaskService {

    /** 租约时长：超过这个时间没心跳，视为执行方已经挂了。 */
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int MAX_REASON = 2000;

    private final HeronResearchTaskMapper researchTaskMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final IResearchAgent researchAgent;
    private final ObjectProvider<Clock> clocks;
    /** 决策服务可选：它不受 {@code heron.agent.enabled} 约束，测试里也可能没有。 */
    private final ObjectProvider<IContentDecisionService> decisionService;
    /**
     * 重复选题检测：这个机会是否已经有待处理或已采纳的决策。
     *
     * <p>为什么在这里拦：建单是花钱的入口。等到跑完再提示重复，token 已经烧掉了。
     *
     * @return 那条决策；没有重复时返回 null
     */
    private HeronContentDecision repeatOf(long opportunityId) {
        IContentDecisionService decisions = decisionService.getIfAvailable();
        if (decisions == null) {
            return null;
        }
        try {
            return decisions.findExistingDecision(opportunityId);
        } catch (RuntimeException e) {
            // 查重失败不该挡住建单：宁可多花一次钱，也不要让用户点不动
            log.warn("重复选题检测失败 opportunityId={} cause={}", opportunityId, e.getMessage());
            return null;
        }
    }

    /** 机会评分；查不到或没评分时返回 null，由 {@link ResearchGrade#of} 降到最低档。 */
    private Double opportunityScore(long opportunityId) {
        try {
            HeronDataOpportunity row = opportunityMapper.selectById(opportunityId);
            return row == null || row.getScore() == null ? null : row.getScore().doubleValue();
        } catch (RuntimeException e) {
            // 读不到评分不该让建单失败：降到最低档继续
            return null;
        }
    }

    /** 是否对「建议生产」的判断再跑一次独立核验；默认开，关掉就是省一份 token。 */
    @Value("${heron.agent.verify-enabled:true}")
    private boolean verifyEnabled;

    /**
     * 研究是长任务，触发接口不能同步等它跑完。
     *
     * <p>队列必须有界：无界队列会让「短时间涌入几百条研究」全部排进来，
     * 每一条都要烧模型额度。满了就拒绝——任务本身还在库里是 queued，
     * 由调度器下一拍再领，不会丢。
     */
    private final ExecutorService runner = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), runnable -> {
            Thread thread = new Thread(runnable, "heron-research-runner");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    @Override
    public long createFor(long opportunityId, String goal) {
        LocalDateTime now = now();
        String dedup = dedupKey(opportunityId, now.toLocalDate());
        HeronResearchTask existing = researchTaskMapper.selectOne(QueryBuilder
            .lambda(HeronResearchTask.class)
            .eq(HeronResearchTask::getDedupKey, dedup)
            .last("LIMIT 1")
            .build());
        if (existing != null) {
            return existing.getId();
        }
        // 同一天的去重键之外，再看有没有跨天的未处理/已采纳决策：
        // 昨天研究过并留了待处理判断的机会，今天再花一次钱重查没有意义
        HeronContentDecision repeated = repeatOf(opportunityId);
        if (repeated != null) {
            // 只有那条决策挂的研究还在时才复用：它可能已被清理，指回去等于返回一个不存在的任务
            HeronResearchTask reusable = researchTaskMapper.selectById(repeated.getResearchId());
            if (reusable != null) {
                log.info("机会 {} 已有待处理或已采纳的决策，复用研究 {}", opportunityId, reusable.getId());
                return reusable.getId();
            }
            log.info("机会 {} 的旧决策仍在但研究 {} 已不存在，按新任务处理",
                opportunityId, repeated.getResearchId());
        }

        long researchId = IdUtil.getSnowflakeNextId();
        // 定时与脚本入口没有登录态，这时才记 0；人工触发要留真实操作人
        long actor = currentUserId();
        // 定档看机会评分：L1 覆盖大多数、L3 只给真正突出的。没评分的按最低档处理
        ResearchGrade grade = ResearchGrade.of(opportunityScore(opportunityId));
        HeronResearchTask row = new HeronResearchTask();
        row.setId(researchId);
        row.setResearchNo("R" + researchId);
        row.setOpportunityId(opportunityId);
        row.setOwnerId(actor);
        row.setActorId(actor);
        // 机会 id 必须进目标：模型只有按 id 查的工具，目标里不带 id 它会直接反问 id，一次工具都不调
        row.setGoal("机会 " + opportunityId + " 在候选里。" + goal);
        row.setSnapshotRef(String.valueOf(opportunityId));
        row.setGrade(grade.code());
        row.setStatus(HeronResearchTask.STATUS_QUEUED);
        row.setAttemptNo(0);
        // 会话标识在创建时定死：同一个任务重试才会续上同一段上下文
        row.setSessionId("research-" + researchId);
        row.setDedupKey(dedup);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            researchTaskMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发点两次：唯一键挡住后到者，回读先到者建的那一行，别把异常抛给用户
            HeronResearchTask raced = researchTaskMapper.selectOne(QueryBuilder
                .lambda(HeronResearchTask.class)
                .eq(HeronResearchTask::getDedupKey, dedup)
                .last("LIMIT 1")
                .build());
            if (raced == null) {
                throw e;
            }
            return raced.getId();
        }
        return researchId;
    }

    @Override
    public String run(long researchId) {
        HeronResearchTask task = researchTaskMapper.selectById(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        LocalDateTime now = now();
        int attempt = (task.getAttemptNo() == null ? 0 : task.getAttemptNo()) + 1;
        // 本次执行的令牌：心跳与所有写回都要对上它，失去它的执行者只能退场
        String runToken = IdUtil.fastSimpleUUID();
        // 领取得认「租约已过期的 running」：执行方挂掉后，这一行不能永远卡在 running
        int claimed = researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
            .set(HeronResearchTask::getAttemptNo, attempt)
            .set(HeronResearchTask::getRunToken, runToken)
            .set(HeronResearchTask::getLeaseUntil, now.plus(LEASE))
            .set(HeronResearchTask::getHeartbeatAt, now)
            .set(HeronResearchTask::getUpdateTime, now)
            .eq(HeronResearchTask::getId, researchId)
            .and(where -> where
                .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
                .or(stale -> stale
                    .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
                    .lt(HeronResearchTask::getLeaseUntil, now)))
            .updateCount();
        if (claimed == 0) {
            // 别人正在跑、已结束或以被取消：不重复跑，直接返回现状（重读，别返回领取前的旧值）
            HeronResearchTask current = find(researchId);
            return current == null ? task.getStatus() : current.getStatus();
        }

        try (ResearchLeaseKeeper keeper = new ResearchLeaseKeeper(
                 researchTaskMapper, researchId, attempt, runToken, clock(), LEASE)) {
            keeper.start();
            // opportunity_id 在库里可空；空的时候不绑定快照，但研究照跑
            long opportunityId = task.getOpportunityId() == null ? 0L : task.getOpportunityId();
            ResearchGrade grade = ResearchGrade.parse(task.getGrade());
            IResearchAgent.Outcome outcome = researchAgent.research(new IResearchAgent.Request(
                task.getSessionId(), researchId, opportunityId, attempt,
                task.getGoal(), grade, keeper::shouldStop));

            var findings = outcome.findings() == null
                ? new IResearchAgent.Findings(null, null, false, null, null)
                : outcome.findings();
            // 丢权意味着这一行已经归别人：连结论一起丢掉，不写回也不落决策
            if (keeper.lost()) {
                log.info("研究 {} 第 {} 次执行已失去执行权，丢弃本次结果", researchId, attempt);
                return currentStatus(researchId, HeronResearchTask.STATUS_RUNNING);
            }
            boolean stopping = keeper.cancelled() || rowStopping(researchId);
            String status;
            if (stopping) {
                // 取消优先于完成：短任务可能在续命线程下一拍之前就跑完了，
                // 这时库里已经是 cancelling，用户要的是「别用这个结果」，不是「其实做完了」。
                // 这一写也是 cancelling → cancelled 的收尾：只有执行方确认退出，才轮到终态
                status = HeronResearchTask.STATUS_CANCELLED;
            } else if (outcome.budgetExceeded() || (outcome.maxItersReached() && !findings.stoppedEarly())) {
                // 预算到点或轮数跑满：结论可用但不完整。
                // 模型自己主动收尾时不算被截断——它已经判断「证据够了」
                status = HeronResearchTask.STATUS_PARTIAL;
            } else {
                status = HeronResearchTask.STATUS_SUCCEEDED;
            }
            int written = researchTaskMapper.lambda()
                .set(HeronResearchTask::getStatus, status)
                .set(HeronResearchTask::getResultText, outcome.text())
                .set(HeronResearchTask::getRounds, outcome.rounds())
                .set(HeronResearchTask::getToolCalls, outcome.toolCalls())
                .set(HeronResearchTask::getInputTokens, outcome.inputTokens())
                .set(HeronResearchTask::getOutputTokens, outcome.outputTokens())
                .set(HeronResearchTask::getDecisionType, findings.decision())
                .set(HeronResearchTask::getConfidence, findings.confidence())
                .set(HeronResearchTask::getEvidenceJson, findings.evidenceJson())
                .set(HeronResearchTask::getReportJson, findings.reportJson())
                .set(HeronResearchTask::getErrorCode, null)
                .set(HeronResearchTask::getErrorReason, null)
                .set(HeronResearchTask::getLeaseUntil, null)
                .set(HeronResearchTask::getUpdateTime, now())
                .eq(HeronResearchTask::getId, researchId)
                .eq(HeronResearchTask::getAttemptNo, attempt)
                .eq(HeronResearchTask::getRunToken, runToken)
                .updateCount();
            if (written == 0) {
                // 写回被拒：这一行已被回收或已被新 attempt 接管，覆盖它等于毁掉别人的结果
                log.info("研究 {} 第 {} 次执行的写回被拒，结果作废", researchId, attempt);
                return currentStatus(researchId, status);
            }
            if (stopping) {
                // 取消优先：被取消的研究不落决策，也不进核验
                return status;
            }
            // 落决策要放在状态写回之后：它读的是刚落库的 report_json。
            // 决策落不下来不该把已经跑完的研究判成失败——研究是研究，决策是它下游的一步。
            Long decisionId = recordDecision(researchId, attempt);
            verifyIfNeeded(task, researchId, attempt, decisionId, grade, keeper::shouldStop);
            return status;
        } catch (RuntimeException e) {
            return finalizeFailure(researchId, attempt, runToken, e);
        }
    }

    @Override
    public HeronResearchTask find(long researchId) {
        return researchTaskMapper.selectById(researchId);
    }

    @Override
    public HeronResearchTask trigger(long researchId) {
        HeronResearchTask task = find(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        try {
            runner.submit(() -> {
                try {
                    run(researchId);
                } catch (RuntimeException e) {
                    log.warn("研究任务异步执行异常 researchId={} cause={}", researchId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：不抛给调用方，任务仍是排队中，调度器下一拍会再领
            log.info("研究线程池已满，任务 {} 留在库里等调度", researchId);
        }
        return task;
    }

    @Override
    public HeronResearchTask requeue(long researchId) {
        if (find(researchId) == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        // 只回退终态：执行中或排队中的重复点击不该把它打回队列。
        // cancelling 也认，但只认租约已过期的——那种情况下执行方已经没了，再等也等不到收尾。
        LocalDateTime now = now();
        researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
            .set(HeronResearchTask::getErrorCode, null)
            .set(HeronResearchTask::getErrorReason, null)
            .set(HeronResearchTask::getRunToken, null)
            .set(HeronResearchTask::getUpdateTime, now)
            .eq(HeronResearchTask::getId, researchId)
            .and(where -> where
                .in(HeronResearchTask::getStatus, List.of(
                    HeronResearchTask.STATUS_SUCCEEDED,
                    HeronResearchTask.STATUS_PARTIAL,
                    HeronResearchTask.STATUS_FAILED,
                    HeronResearchTask.STATUS_CANCELLED))
                .or(abandoned -> abandoned
                    .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_CANCELLING)
                    .lt(HeronResearchTask::getLeaseUntil, now)))
            .updateCount();
        return trigger(researchId);
    }

    @Override
    public HeronResearchTask cancel(long researchId) {
        HeronResearchTask task = find(researchId);
        if (task == null) {
            throw new HeronBusinessException(404, "research_not_found", "研究任务不存在：" + researchId);
        }
        LocalDateTime now = now();
        // 先处理排队中：它没有执行方，直接落终态。
        // 顺序不能反——先查 running 会在「刚被领走」的瞬间漏掉，取消就丢了。
        int cancelled = researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_CANCELLED)
            .set(HeronResearchTask::getUpdateTime, now)
            .eq(HeronResearchTask::getId, researchId)
            .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
            .updateCount();
        if (cancelled == 0) {
            // 执行中的落 cancelling：Agent 要等续命下一拍才停，这期间它还可能写回结果，
            // 所以现在写 cancelled 是撒谎，得由执行方自己确认退出
            researchTaskMapper.lambda()
                .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_CANCELLING)
                .set(HeronResearchTask::getUpdateTime, now)
                .eq(HeronResearchTask::getId, researchId)
                .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
                .updateCount();
        }
        return find(researchId);
    }

    @Override
    public List<Long> claimableIds(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        LocalDateTime now = now();
        return researchTaskMapper.lambda()
            .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_QUEUED)
            .or(stale -> stale
                .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_RUNNING)
                .lt(HeronResearchTask::getLeaseUntil, now))
            .orderByAsc(HeronResearchTask::getCreateTime)
            .last("LIMIT " + limit)
            .list()
            .stream()
            .map(HeronResearchTask::getId)
            .toList();
    }

    @Override
    public int resolveAbandonedCancels() {
        LocalDateTime now = now();
        // 领取时一定会写 lease_until，而 cancelling 只在执行中被取消时出现，
        // 所以「租约已过期」就足够判定执行方已经不在了
        return researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, HeronResearchTask.STATUS_CANCELLED)
            .set(HeronResearchTask::getLeaseUntil, null)
            .set(HeronResearchTask::getUpdateTime, now)
            .eq(HeronResearchTask::getStatus, HeronResearchTask.STATUS_CANCELLING)
            .lt(HeronResearchTask::getLeaseUntil, now)
            .updateCount();
    }

    @Override
    public PageResult<ResearchTaskVo> queryPageList(ResearchTaskQueryBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<HeronResearchTask> wrapper = QueryBuilder
            .lambda(HeronResearchTask.class)
            .eqIfPresent(HeronResearchTask::getOpportunityId, bo.getOpportunityId())
            .eqIfText(HeronResearchTask::getStatus, bo.getStatus())
            .eqIfText(HeronResearchTask::getDecisionType, bo.getDecisionType())
            .orderByDesc(HeronResearchTask::getCreateTime)
            .build();
        var page = researchTaskMapper.selectVoPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    /** 库里是否已经在取消中：续命线程来不及看到时，靠这一次回读兜住。 */
    private boolean rowStopping(long researchId) {
        HeronResearchTask current = researchTaskMapper.selectById(researchId);
        String status = current == null ? null : current.getStatus();
        return HeronResearchTask.STATUS_CANCELLING.equals(status)
            || HeronResearchTask.STATUS_CANCELLED.equals(status);
    }

    /** 写回被拒时返回库里的真实状态，不把本次尝试的结论当成事实报出去。 */
    private String currentStatus(long researchId, String fallback) {
        HeronResearchTask current = researchTaskMapper.selectById(researchId);
        return current == null ? fallback : current.getStatus();
    }

    /**
     * 失败收尾：已在取消的落 cancelled 而不是 failed——用户点的取消不该显示成失败。
     *
     * <p>写回仍然带令牌：这一行已经被别人接走时，连失败也不该由旧执行者来写。
     */
    private String finalizeFailure(long researchId, int attempt, String runToken, RuntimeException e) {
        log.warn("研究任务失败 researchId={} attempt={} cause={}", researchId, attempt, e.getMessage());
        HeronResearchTask row = researchTaskMapper.selectById(researchId);
        String current = row == null ? null : row.getStatus();
        boolean stopping = HeronResearchTask.STATUS_CANCELLING.equals(current)
            || HeronResearchTask.STATUS_CANCELLED.equals(current);
        String target = stopping ? HeronResearchTask.STATUS_CANCELLED : HeronResearchTask.STATUS_FAILED;
        int updated = researchTaskMapper.lambda()
            .set(HeronResearchTask::getStatus, target)
            .set(HeronResearchTask::getErrorCode, stopping ? null : "research_failed")
            .set(HeronResearchTask::getErrorReason, stopping ? null : truncate(e.getMessage()))
            .set(HeronResearchTask::getLeaseUntil, null)
            .set(HeronResearchTask::getUpdateTime, now())
            .eq(HeronResearchTask::getId, researchId)
            .eq(HeronResearchTask::getAttemptNo, attempt)
            .eq(HeronResearchTask::getRunToken, runToken)
            .updateCount();
        return updated == 0 ? currentStatus(researchId, target) : target;
    }

    /** 决策落库失败只记日志：研究已经跑完且有结论，不能因为下游没接住就连结论一起丢。 */
    private Long recordDecision(long researchId, int attempt) {
        IContentDecisionService decisions = decisionService.getIfAvailable();
        if (decisions == null) {
            return null;
        }
        try {
            return decisions.recordFromResearch(researchId, attempt);
        } catch (RuntimeException e) {
            log.warn("研究结论转决策失败 researchId={} attempt={} cause={}", researchId, attempt, e.getMessage());
            return null;
        }
    }

    /**
     * 独立核验，只对最高档且「建议生产」的判断跑。
     *
     * <p>两道条件缺一不可：
     * <ul>
     *   <li>等级 L3——核验要再花一次完整 Agent 调用，中低档机会不值得；</li>
     *   <li>判断 PRODUCE——判错成「不值得做」只是少做一条，判错成「值得做」会一路走到生产。</li>
     * </ul>
     * 这也符合 plan.md §12「只有达到相应价值阈值的内容才升级到更高等级」与 §16「多 Agent 不强制全上」。
     *
     * <p>核验失败只记日志：它是结论的下游校验，不能把已经跑完的研究一起带崩。
     */
    private void verifyIfNeeded(HeronResearchTask task, long researchId, int attempt,
                                Long decisionId, ResearchGrade grade, BooleanSupplier cancelled) {
        if (decisionId == null || !verifyEnabled || grade == null || !grade.verifies()) {
            return;
        }
        IContentDecisionService decisions = decisionService.getIfAvailable();
        if (decisions == null) {
            return;
        }
        try {
            HeronContentDecision decision = decisions.findDecisionById(decisionId);
            if (decision == null || !HeronContentDecision.DECISION_PRODUCE.equals(decision.getDecisionType())) {
                return;
            }
            String claim = "判断：" + decision.getDecisionType()
                + "\n理由：" + decision.getDecisionReason()
                + "\n依据的快照标识：" + (decision.getEvidenceRef() == null ? "未绑定" : decision.getEvidenceRef());
            IResearchAgent.Verification result = researchAgent.verify(new IResearchAgent.VerifyRequest(
                "verify-" + researchId + "-" + attempt, researchId, attempt,
                task.getGoal(), claim, cancelled));
            if (result.submitted()) {
                decisions.attachVerification(decisionId, result.verdict(), result.reason(),
                    result.conflictsJson());
                if (HeronContentDecision.VERDICT_DISAGREE.equals(result.verdict())) {
                    // 冲突是最需要人看的状态：日志留痕，页面上也会以红色标出
                    log.warn("核验与研究员结论冲突 researchId={} decisionId={} reason={}",
                        researchId, decisionId, result.reason());
                }
            }
        } catch (RuntimeException e) {
            log.warn("核验失败 researchId={} cause={}", researchId, e.getMessage());
        }
    }

    /** 同一机会同一天只研究一次：日期参与摘要，跨天才有新任务。 */
    public static String dedupKey(long opportunityId, LocalDate date) {
        return DigestUtil.sha256Hex(opportunityId + "|" + date);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    /** 无登录上下文（定时/脚本）时返回 0，不抛异常。 */
    private static long currentUserId() {
        try {
            Long userId = LoginHelper.getUserId();
            return userId == null ? 0L : userId;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock());
    }

    private Clock clock() {
        Clock clock = clocks.getIfAvailable();
        return clock == null ? Clock.systemDefaultZone() : clock;
    }
}
