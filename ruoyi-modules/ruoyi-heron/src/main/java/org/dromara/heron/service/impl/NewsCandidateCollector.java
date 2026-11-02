package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsSourceMapper;
import org.dromara.heron.mapper.HeronProductionLaneMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 采集写入：lane → source → candidate 的固定锁序，一批条目一个事务。
 * 令牌校验先于任何写入，迟到的 worker 既写不进候选也定稿不了成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCandidateCollector {

    private static final ProductionBizType TYPE = ProductionBizType.news_video;
    private final HeronProductionLaneMapper laneMapper;
    private final HeronNewsSourceMapper sourceMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final ObjectProvider<Clock> clocks;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void collect(long sourceId, String fetchToken, List<NewsFeedItem> items) {
        if (laneMapper.lockByType(TYPE) == null) {
            throw new HeronBusinessException(503, "news_lane_missing", "新闻生产线未初始化");
        }
        HeronNewsSource source = sourceMapper.lockById(sourceId);
        if (source == null) throw new HeronAccessException();
        if (source.getFetchToken() == null || !source.getFetchToken().equals(fetchToken)) {
            throw new HeronBusinessException(409, "news_stale_token", "采集令牌已失效");
        }
        LocalDateTime now = now();
        List<HeronNewsCandidate> recent = null;
        for (NewsFeedItem item : items) {
            if (recent == null) recent = recentCandidates(now.minusHours(48));
            upsert(source, item, recent, now);
        }
        if (sourceMapper.finalizeSuccess(sourceId, fetchToken, now,
            now.plusSeconds(source.getIntervalSeconds() == null ? 600 : source.getIntervalSeconds())) != 1) {
            throw new HeronBusinessException(409, "news_stale_token", "采集定稿令牌已失效");
        }
    }

    private void upsert(HeronNewsSource source, NewsFeedItem item, List<HeronNewsCandidate> recent, LocalDateTime now) {
        if (item.title() == null || item.title().isBlank()) return;
        String title = cut(item.title().strip(), 200);
        String summary = cut(item.summary() == null ? "" : item.summary().replace("\r\n", "\n").replace('\r', '\n').strip(), 16000);
        String normalized = cut(NewsDedup.normalizedTitle(title), 200);
        // 只剩标点的标题不构成一条新闻，跳过而不是写空候选。
        if (normalized.isEmpty()) return;
        String canonical = NewsUrlPolicy.canonical(item.url());
        String key = DigestUtil.sha256Hex(canonical == null ? normalized : canonical);
        String contentHash = DigestUtil.sha256Hex(normalized + "\n" + summary);
        HeronNewsCandidate matched = candidateMapper.selectOne(QueryBuilder.lambda(HeronNewsCandidate.class)
            .eq(HeronNewsCandidate::getDedupKey, key).build());
        if (matched == null) {
            matched = recent.stream().filter(c -> NewsDedup.sameNews(normalized, c.getNormalizedTitle()))
                .findFirst().orElse(null);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceId", source.getSourceId());
        evidence.put("sourceUrl", canonical);
        evidence.put("manual", false);
        evidence.put("fetchedAt", now.toString());
        evidence.put("title", title);
        evidence.put("summary", summary);
        evidence.put("sourceItemId", item.itemId());
        evidence.put("contentSha256", contentHash);
        if (matched == null) {
            HeronNewsCandidate created = new HeronNewsCandidate();
            created.setCandidateId(IdUtil.getSnowflakeNextId());
            created.setTitle(title);
            created.setSummary(summary);
            created.setNormalizedTitle(normalized);
            created.setDedupKey(key);
            created.setHitCount(1L);
            created.setHeatScore(1L);
            created.setBestRank(item.rank());
            created.setFirstSeenAt(now);
            created.setLastSeenAt(now);
            created.setEvidence(JsonUtils.toJsonString(Map.of("items", List.of(evidence), "evidenceTruncated", false)));
            created.setCreateBy(source.getCreateBy());
            created.setUpdateBy(source.getCreateBy());
            created.setCreateTime(now);
            created.setUpdateTime(now);
            if (candidateMapper.insert(created) != 1) {
                throw new HeronBusinessException(409, "news_candidate_write", "候选保存失败");
            }
            recent.add(created);
            return;
        }
        matched = candidateMapper.lockById(matched.getCandidateId());
        // 已发布候选是只读事实：同一链接再来一次也不复活、不刷热度。
        if (matched.getPublishedAt() != null) return;
        var merged = NewsDedup.merge(matched.getEvidence(), evidence, (stored, incoming) ->
            Objects.equals(stored.get("sourceItemId"), incoming.get("sourceItemId"))
                && Objects.equals(stored.get("contentSha256"), incoming.get("contentSha256")));
        Integer best = betterRank(matched.getBestRank(), item.rank());
        Long editor = source.getCreateBy();
        candidateMapper.lambda().set(HeronNewsCandidate::getEvidence, merged.evidence())
            .set(HeronNewsCandidate::getHitCount,
                merged.repeated() ? matched.getHitCount() : Math.addExact(matched.getHitCount(), 1L))
            .set(HeronNewsCandidate::getHeatScore,
                merged.repeated() ? matched.getHeatScore() : Math.addExact(matched.getHeatScore(), 1L))
            .set(best != null, HeronNewsCandidate::getBestRank, best)
            .set(HeronNewsCandidate::getLastSeenAt, now).set(HeronNewsCandidate::getUpdateTime, now)
            .set(editor != null, HeronNewsCandidate::getUpdateBy, editor)
            .eq(HeronNewsCandidate::getCandidateId, matched.getCandidateId()).update();
    }

    /** 48 小时窗口内的近期候选，一批条目共用一次扫描。 */
    private List<HeronNewsCandidate> recentCandidates(LocalDateTime since) {
        return new ArrayList<>(candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
            .ge(HeronNewsCandidate::getLastSeenAt, since)
            .orderByDesc(HeronNewsCandidate::getLastSeenAt, HeronNewsCandidate::getCandidateId)
            .last("LIMIT 5000").build()));
    }

    private static Integer betterRank(Integer stored, Integer incoming) {
        if (incoming == null) return stored;
        if (stored == null) return incoming;
        return Math.min(stored, incoming);
    }

    private static String cut(String value, int codePoints) {
        int total = value.codePointCount(0, value.length());
        return total <= codePoints ? value : value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clocks.getIfAvailable(Clock::systemUTC));
    }
}
