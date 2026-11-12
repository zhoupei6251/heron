package org.dromara.heron.service;

import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归因 SQL 从快照的 selection / editorial 节点取维度，走的是 JSON 函数与 choose 分支。
 * 这类语句只有真跑一次才知道绑定与取值对不对。
 */
@DisplayName("表现归因：按模板/生产线/角度汇总")
class MetricBreakdownTest extends NewsMysqlTestSupport {

    @Autowired private INewsMetricService metricService;

    @Test
    @DisplayName("按模板分组，同一版本多次采样只算最新一次")
    void groupsByTemplate() {
        // 同一个版本采两次：旧的那次不能参与平均，否则看起来像这一版越跑越差
        long versionId = seedVersion("news-9x16", "DATA_BOARD", "角度甲");
        metric(versionId, 100L, "0.3000", "2026-11-01 10:00:00");
        metric(versionId, 900L, "0.9000", "2026-11-02 10:00:00");

        List<HeronNewsMetricMapper.Breakdown> rows = metricService.performanceBreakdown("template");
        HeronNewsMetricMapper.Breakdown bucket = rows.stream()
            .filter(row -> "news-9x16".equals(row.bucket())).findFirst().orElseThrow();
        assertEquals(1, bucket.samples(), "同一版本只算一条样本");
        assertEquals(900.0, bucket.avgPlays(), 0.01, "旧采样不能参与平均");
    }

    @Test
    @DisplayName("按生产线分组")
    void groupsByLine() {
        seed("news-9x16", "NEWS_EXPLAINER", "角度甲", 500L, "0.5000", "2026-11-05 10:00:00");
        List<HeronNewsMetricMapper.Breakdown> rows = metricService.performanceBreakdown("line");
        assertEquals(1, rows.size());
        assertEquals("NEWS_EXPLAINER", rows.get(0).bucket());
    }

    @Test
    @DisplayName("按角度分组，空角度归入未记录而不是当成一个角度")
    void groupsByAngleAndFallsBack() {
        seed("news-9x16", "NEWS_EXPLAINER", "", 700L, "0.7000", "2026-11-06 10:00:00");
        List<HeronNewsMetricMapper.Breakdown> rows = metricService.performanceBreakdown("angle");
        assertEquals(1, rows.size());
        assertEquals("未记录", rows.get(0).bucket());
    }

    @Test
    @DisplayName("未知维度被拒，不把任意值带进 SQL")
    void rejectsUnknownDimension() {
        assertThrows(org.dromara.heron.domain.HeronBusinessException.class,
            () -> metricService.performanceBreakdown("plays; drop table"));
    }

    private void seed(String templateId, String line, String angle, long plays,
                      String completionRate, String fetchedAt) {
        metric(seedVersion(templateId, line, angle), plays, completionRate, fetchedAt);
    }

    private long seedVersion(String templateId, String line, String angle) {
        long candidateId = next();
        jdbc.update("insert into heron_news_candidate(candidate_id, title, summary, evidence, dedup_key, "
                + "normalized_title, hit_count, heat_score, first_seen_at, last_seen_at, create_time, update_time) "
                + "values(?,?,?,'{}',?,?,?,?,now(),now(),now(),now())",
            candidateId, "标题" + candidateId, "摘要", "d" + candidateId, "t" + candidateId, 1L, 0L);
        long workId = next();
        jdbc.update("insert into heron_news_work(work_id, candidate_id, owner_id, title, draft, revision, "
                + "next_version, template_key, voice_key, auto_publish, create_time, update_time) "
                + "values(?,?,?,?,?,?,?,?,?,?,now(),now())",
            workId, candidateId, OWNER, "作品", "草稿", 1, 1, templateId, "v", "1");
        long versionId = next();
        String snapshot = """
            {"selection":{"templateId":"%s"},"editorial":{"line":"%s","storyAngle":"%s"}}
            """.formatted(templateId, line, angle).strip();
        // task_id 有唯一键约束，每个版本必须给不同的值
        jdbc.update("insert into heron_video_version(version_id, work_id, owner_id, version_no, task_id, "
                + "input_snapshot, review_status, create_time, update_time) values(?,?,?,?,?,?,?,now(),now())",
            versionId, workId, OWNER, 1, next(), snapshot, "approved");
        return versionId;
    }

    private void metric(long versionId, long plays, String completionRate, String fetchedAt) {
        jdbc.update("insert into heron_news_metric(metric_id, version_id, platform, plays, completion_rate, "
                + "likes, fetched_at, create_time) values(?,?,?,?,?,?,?,now())",
            next(), versionId, "douyin", plays, new java.math.BigDecimal(completionRate), 10L, fetchedAt);
    }

    private static long next() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
    }
}
