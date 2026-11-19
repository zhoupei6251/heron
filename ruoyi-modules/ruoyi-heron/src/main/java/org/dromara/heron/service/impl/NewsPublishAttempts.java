package org.dromara.heron.service.impl;

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

    @Transactional(propagation = Propagation.MANDATORY)
    public HeronNewsPublishAttempt active(long candidateId) {
        return mapper.lockActive(candidateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public HeronNewsPublishAttempt lock(long id) {
        HeronNewsPublishAttempt attempt = mapper.lockById(id);
        if (attempt == null) throw conflict("发布尝试不存在");
        return attempt;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public HeronNewsPublishAttempt begin(long candidateId, long workId, long versionId, String sha,
                                         String source, String account, long actor, LocalDateTime now) {
        if (sha == null || !sha.matches("[0-9a-f]{64}") || source == null || !source.matches("[a-z0-9-]{1,12}")) {
            throw conflict("发布来源或指纹不合法");
        }
        HeronNewsPublishAttempt existing = active(candidateId);
        if (existing != null) throw conflict("同一选题已有未处置的发布尝试");
        var attempt = new HeronNewsPublishAttempt();
        attempt.setAttemptId(IdUtil.getSnowflakeNextId());
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(HeronNewsPublishAttempt attempt, String status, String account, LocalDateTime now) {
        if (!Set.of("submitted", "published").contains(status) || !"started".equals(attempt.getStatus())
            || account == null || account.isBlank() || account.length() > 120) throw conflict("发布结果不合法");
        change(attempt, status, account.strip(), null, now, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unknown(long id, String failureCode, LocalDateTime now) {
        // 若完成事务已提交而响应丢失，不能把已提交/已发布状态倒退为unknown。
        mapper.transition(id, "started", "unknown", null, failureCode, now, null, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(HeronNewsPublishAttempt attempt, String outcome, String account, long actor,
                        String reason, boolean uploadStopped, int timeoutSeconds, LocalDateTime now) {
        requireResolvable(attempt, outcome, reason, uploadStopped, timeoutSeconds, now);
        change(attempt, outcome, account, null, now, actor, reason.strip());
    }

    static void requireResolvable(HeronNewsPublishAttempt attempt, String outcome, String reason,
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
        if (mapper.transition(attempt.getAttemptId(), attempt.getStatus(), next, account, failureCode,
            now, actor, actor == null ? null : now, reason) != 1) throw conflict("发布尝试状态已变化");
    }

    public Map<Long, NewsPublishAttemptVo> latest(List<Long> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        return mapper.latestForVersions(versionIds).stream().map(row -> MapstructUtils.convert(row, NewsPublishAttemptVo.class))
            .collect(Collectors.toMap(NewsPublishAttemptVo::getVersionId, Function.identity()));
    }

    private static HeronBusinessException conflict(String reason) {
        return new HeronBusinessException(409, "news_publish_attempt_conflict", reason);
    }
}
