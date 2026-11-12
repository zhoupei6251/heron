package org.dromara.heron.service;

import org.dromara.heron.domain.vo.NewsWorkVo;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 认领成功、提交失败后的补偿：候选必须回到池子里。
 * 不回收的话它一直带着 claimed_by，建池查询（claimed_by is null）从此看不见它，
 * 而且不会有任何报错——失败一次等于永久拉黑。
 */
@DisplayName("自动认领：失败补偿")
class AutoClaimReleaseTest extends NewsMysqlTestSupport {

    private long candidateId() {
        return news.addManual(manual("补偿候选" + UUID.randomUUID(), "来源")).getCandidateId();
    }

    @Test
    @DisplayName("没产出过版本：释放候选与作品占用")
    void releasesClaimWithoutVersion() {
        long candidate = candidateId();
        NewsWorkVo work = news.claim(candidate);
        assertEquals(OWNER, jdbc.queryForObject(
            "SELECT claimed_by FROM heron_news_candidate WHERE candidate_id=?", Long.class, candidate));

        maintenance.abandonAuto(work.getWorkId());

        assertNull(jdbc.queryForObject(
            "SELECT claimed_by FROM heron_news_candidate WHERE candidate_id=?", Long.class, candidate));
        assertNull(jdbc.queryForObject(
            "SELECT claimed_at FROM heron_news_candidate WHERE candidate_id=?", java.time.LocalDateTime.class, candidate));
        assertNull(jdbc.queryForObject(
            "SELECT active_task_id FROM heron_news_work WHERE work_id=?", Long.class, work.getWorkId()));
    }

    @Test
    @DisplayName("已经产出过版本：不回收，避免重试失败把在用的认领清掉")
    void keepsClaimOnceVersionExists() {
        long candidate = candidateId();
        NewsWorkVo work = news.claim(candidate);
        jdbc.update("UPDATE heron_news_work SET current_version_id=999 WHERE work_id=?", work.getWorkId());

        maintenance.abandonAuto(work.getWorkId());

        assertEquals(OWNER, jdbc.queryForObject(
            "SELECT claimed_by FROM heron_news_candidate WHERE candidate_id=?", Long.class, candidate));
    }
}
