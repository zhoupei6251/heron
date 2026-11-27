package org.dromara.heron.service.impl.shared.publish;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsPublishAttempt;
import org.dromara.heron.domain.vo.NewsPublishAttemptVo;
import org.dromara.heron.mapper.HeronNewsPublishAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 调用者先锁候选/作品，再锁尝试；状态写入必须与各自短事务一同提交。 */
@Service
@RequiredArgsConstructor
public class NewsPublishAttempts {
    private final HeronNewsPublishAttemptMapper mapper;

    /**
     * 锁读选题当前的活动尝试（status 为 started/unknown/submitted/published 的那条，由生成列唯一键保证至多一条）。
     *
     * @return 活动尝试；没有活动尝试时返回 null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HeronNewsPublishAttempt active(long candidateId) {
        return mapper.lockActive(candidateId);
    }

    /**
     * 按 ID 锁读一条尝试。
     *
     * @throws HeronBusinessException 409 {@code news_publish_attempt_conflict}，尝试不存在
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HeronNewsPublishAttempt lock(long id) {
        HeronNewsPublishAttempt attempt = mapper.lockById(id);
        if (attempt == null) throw conflict("发布尝试不存在");
        return attempt;
    }

    /**
     * 登记一次发布尝试并置为 started。上传是有副作用的动作，必须先落账再执行。
     *
     * @param sha    成片指纹，必须是 64 位小写十六进制
     * @param source 发布出口标识，只允许小写字母、数字与连字符（1–12 位）
     * @param account 目标账号，可为空；非空时去空白后写入
     * @param actor  发起人用户 ID
     * @return 新插入的尝试
     * @throws HeronBusinessException 409 {@code news_publish_attempt_conflict}：指纹/来源/账号非法，
     *         同一选题已有未处置的尝试，或插入失败
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HeronNewsPublishAttempt begin(long candidateId, long workId, long versionId, String sha,
                                         String source, String account, long actor, LocalDateTime now) {
        if (sha == null || !sha.matches("[0-9a-f]{64}") || source == null || !source.matches("[a-z0-9-]{1,12}")) {
            throw conflict("发布来源或指纹不合法");
        }
        HeronNewsPublishAttempt existing = active(candidateId);
        if (existing != null) throw conflict("同一选题已有未处置的发布尝试");
        var attempt = new HeronNewsPublishAttempt();
        attempt.setId(IdUtil.getSnowflakeNextId());
        attempt.setCandidateId(candidateId);
        attempt.setWorkId(workId);
        attempt.setVersionId(versionId);
        attempt.setVideoSha(sha);
        attempt.setSource(source);
        if (account != null) {
            if (account.isBlank() || account.length() > 120 || account.indexOf('\0') >= 0) throw conflict("目标账号不合法");
            attempt.setAccount(account.strip());
        }
        attempt.setStatus("started");
        attempt.setStartedAt(now);
        attempt.setUpdatedAt(now);
        attempt.setActorId(actor);
        if (mapper.insert(attempt) != 1) throw conflict("发布尝试未能持久化，不能上传");
        return attempt;
    }

    /**
     * 上传成功收尾：把 started 尝试推进到 submitted 或 published，并记下实际目标账号。
     *
     * @throws HeronBusinessException 409 {@code news_publish_attempt_conflict}：状态不是 started、
     *         目标状态不在这两个值内、账号为空或超长，或状态已被并发改变
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(HeronNewsPublishAttempt attempt, String status, String account, LocalDateTime now) {
        if (!Set.of("submitted", "published").contains(status) || !"started".equals(attempt.getStatus())
            || account == null || account.isBlank() || account.length() > 120) throw conflict("发布结果不合法");
        change(attempt, status, account.strip(), null, now, null, null);
    }

    /**
     * 上传结果未知时把 started 推进到 unknown，保留活动锁，等人工核对后由 {@link #resolve} 释放。
     * 只有仍是 started 才会更新（已 submitted/published 说明完成事务已提交，不能倒退），否则静默不改。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unknown(long id, String failureCode, LocalDateTime now) {
        // 若完成事务已提交而响应丢失，不能把已提交/已发布状态倒退为unknown。
        mapper.transition(id, "started", "unknown", null, failureCode, now, null, null, null);
    }

    /**
     * 人工核对后落结论：started/unknown/submitted → published 或 not_published（not_published 会释放选题的发布锁）。
     *
     * @param uploadStopped  是否已确认上传停止；判 not_published 时必须为 true
     * @param timeoutSeconds 上传超时配置，用于计算「上传可能仍在跑」的最短等待
     * @throws HeronBusinessException 409 {@code news_publish_attempt_conflict}：结论/理由非法、
     *         该尝试已核对、未确认上传停止，或 started 状态距开始时间不足 {@code max(60, timeoutSeconds)+120} 秒
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(HeronNewsPublishAttempt attempt, String outcome, String account, long actor,
                        String reason, boolean uploadStopped, int timeoutSeconds, LocalDateTime now) {
        requireResolvable(attempt, outcome, reason, uploadStopped, timeoutSeconds, now);
        change(attempt, outcome, account, null, now, actor, reason.strip());
    }

    /**
     * 核对结论的前置校验：结论必须是 published/not_published，理由非空、不超 1000 字且不含 NUL；
     * 尝试只能是 started/unknown/submitted；判 not_published 还必须确认上传已停止，
     * 且 started 状态要等过 {@code max(60, timeoutSeconds)+120} 秒。不满足一律 409。
     */
    public static void requireResolvable(HeronNewsPublishAttempt attempt, String outcome, String reason,
                                  boolean uploadStopped, int timeoutSeconds, LocalDateTime now) {
        if (!Set.of("published", "not_published").contains(Objects.toString(outcome, "")) || reason == null || reason.isBlank()
            || reason.length() > 1000 || reason.indexOf('\0') >= 0) throw conflict("核对结论与理由不合法");
        if (!Set.of("started", "unknown", "submitted").contains(Objects.toString(attempt.getStatus(), ""))) throw conflict("该尝试已核对，不能覆盖结论");
        if ("not_published".equals(outcome)) {
            if (!uploadStopped) throw conflict("必须先确认上传已停止，且平台确实未发布");
            if ("started".equals(attempt.getStatus()) && (attempt.getStartedAt() == null
                || now.isBefore(attempt.getStartedAt().plusSeconds(Math.max(60, timeoutSeconds) + 120L)))) {
                throw conflict("上传可能仍在运行，暂不能解除重复发布保护");
            }
        }
    }

    private void change(HeronNewsPublishAttempt attempt, String next, String account, String failureCode,
                        LocalDateTime now, Long actor, String reason) {
        if (mapper.transition(attempt.getId(), attempt.getStatus(), next, account, failureCode,
            now, actor, actor == null ? null : now, reason) != 1) throw conflict("发布尝试状态已变化");
    }

    /**
     * 批量查每个版本最新的一条尝试，用于列表展示。
     *
     * @param versionIds 版本 ID 列表；空列表直接返回空 Map，不查库
     * @return versionId → 最新尝试视图
     */
    public Map<Long, NewsPublishAttemptVo> latest(List<Long> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        return mapper.latestForVersions(versionIds).stream().map(row -> MapstructUtils.convert(row, NewsPublishAttemptVo.class))
            .collect(Collectors.toMap(NewsPublishAttemptVo::getVersionId, Function.identity()));
    }

    private static HeronBusinessException conflict(String reason) {
        return new HeronBusinessException(409, "news_publish_attempt_conflict", reason);
    }
}
