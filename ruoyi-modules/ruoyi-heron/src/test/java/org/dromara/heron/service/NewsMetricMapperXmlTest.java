package org.dromara.heron.service;

import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联表 SQL 从注解搬到 XML 之后，必须真的执行一次才算验证过：
 * MyBatis 在启动时才解析 XML，编译通过不代表语句能绑定。
 */
@DisplayName("表现联查：XML 语句能绑定并返回最新采样")
class NewsMetricMapperXmlTest extends NewsMysqlTestSupport {

    @Autowired private HeronNewsMetricMapper metricMapper;

    @Test
    @DisplayName("同一版本多次采样只取最新一条，并带上选题标题")
    void takesLatestSamplePerVersion() {
        long candidateId = insertCandidate("联查标题");
        long workId = insertWork(candidateId);
        long versionId = insertVersion(workId);
        insertMetric(versionId, 100L, "0.2000", "2026-11-01 10:00:00");
        insertMetric(versionId, 900L, "0.8000", "2026-11-02 10:00:00");

        List<HeronNewsMetricMapper.Performance> rows = metricMapper.recentPerformance(10);

        assertEquals(1, rows.size(), "同一版本只能出一行");
        assertEquals("联查标题", rows.get(0).title());
        assertEquals(900L, rows.get(0).plays(), "必须取最新那次采样");
        assertEquals(0, rows.get(0).completionRate().compareTo(new java.math.BigDecimal("0.8000")));
    }

    @Test
    @DisplayName("没有采样的版本不出现在结果里")
    void ignoresVersionsWithoutMetric() {
        long candidateId = insertCandidate("没有数据");
        long workId = insertWork(candidateId);
        insertVersion(workId);

        assertTrue(metricMapper.recentPerformance(10).isEmpty());
    }

    private long insertCandidate(String title) {
        long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
        jdbc.update("insert into heron_news_candidate(candidate_id, title, summary, evidence, dedup_key, "
                + "normalized_title, hit_count, heat_score, first_seen_at, last_seen_at, create_time, update_time) "
                + "values(?,?,?,'{}',?,?,?,?,now(),now(),now(),now())",
            id, title, "摘要", "dedup-" + id, title.toLowerCase(), 1L, 0L);
        return id;
    }

    private long insertWork(long candidateId) {
        long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
        jdbc.update("insert into heron_news_work(work_id, candidate_id, owner_id, title, draft, revision, next_version, "
                + "template_key, voice_key, auto_publish, create_time, update_time) "
                + "values(?,?,?,?,?,?,?,?,?,?,now(),now())",
            id, candidateId, OWNER, "作品", "草稿", 1, 1, "default", "default", "1");
        return id;
    }

    private long insertVersion(long workId) {
        long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
        jdbc.update("insert into heron_video_version(version_id, work_id, owner_id, version_no, task_id, "
                + "input_snapshot, review_status, create_time, update_time) values(?,?,?,?,?,?,?,now(),now())",
            id, workId, OWNER, 1, 1L, "{}", "approved");
        return id;
    }

    private void insertMetric(long versionId, long plays, String completionRate, String fetchedAt) {
        long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
        jdbc.update("insert into heron_news_metric(metric_id, version_id, platform, plays, completion_rate, "
                + "fetched_at, create_time) values(?,?,?,?,?,?,now())",
            id, versionId, "douyin", plays, new java.math.BigDecimal(completionRate), fetchedAt);
    }
}
