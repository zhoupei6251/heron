package org.dromara.heron.service;

import org.dromara.heron.controller.HeronExceptionHandler;
import org.dromara.heron.controller.NewsWorkController;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.NewsPublishBo;
import org.dromara.heron.domain.vo.NewsVideoVersionVo;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** G3 人工发布回执：一个候选只有一条已发布事实，重复回填不产生第二条记录。 */
class NewsPublishAccessTest extends NewsMysqlTestSupport {
    private MockMvc mvc;

    @BeforeEach
    void http() {
        mvc = MockMvcBuilders.standaloneSetup(new NewsWorkController(news))
            .setControllerAdvice(new HeronExceptionHandler()).build();
    }

    @Test
    void receiptWritesVersionAndCandidateFactsAndReplaysWithoutSecondRecord() throws Exception {
        var first = completed();
        news.review(first.workId(), first.versionId(), first.approve());
        NewsPublishBo receipt = publish(first);

        NewsVideoVersionVo vo = news.publish(first.workId(), first.versionId(), receipt);
        assertEquals("manual", vo.getPublishSource());
        assertEquals(receipt.videoSha256(), vo.getPublishVideoSha());
        assertEquals(receipt.publishedAt().withNano(0), vo.getPublishAt().withNano(0));
        assertEquals(firstOwner(), vo.getPublishBy());
        assertEquals(vo.getPublishAt(), publishedAtOf(first.workId()));
        assertEquals(1, publishRowCount(first.workId()));

        assertEquals(vo.getPublishAt(), news.publish(first.workId(), first.versionId(), receipt).getPublishAt());
        assertEquals(1, publishRowCount(first.workId()));
        assertEquals(vo.getPublishAt(), publishedAtOf(first.workId()));

        mvc.perform(get("/heron/newsWork/{w}/versions/{v}", first.workId(), first.versionId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.publishSource").value("manual"))
            .andExpect(jsonPath("$.data.publishUrl").value(receipt.workUrl()));
    }

    @Test
    void changedFactsAndSecondVersionCannotOverwriteThePublishedFact() throws Exception {
        var work = work();
        var first = completed(work);
        var second = completed(work);
        news.review(first.workId(), first.versionId(), first.approve());
        news.review(second.workId(), second.versionId(), second.approve());
        news.publish(first.workId(), first.versionId(), publish(first));

        HeronBusinessException changed = assertThrows(HeronBusinessException.class,
            () -> news.publish(first.workId(), first.versionId(),
                new NewsPublishBo("https://www.douyin.com/video/7412345678901234999", publish(first).account(),
                    publish(first).publishedAt(), publish(first).videoSha256())));
        assertEquals("news_publish_conflict", changed.getCode());

        HeronBusinessException other = assertThrows(HeronBusinessException.class,
            () -> news.publish(second.workId(), second.versionId(), publish(second)));
        assertEquals("news_publish_conflict", other.getCode());
        assertEquals(1, publishRowCount(first.workId()));
    }

    @Test
    void approvalHashPermissionAndFutureTimeGateTheReceipt() throws Exception {
        var pending = completed();
        assertEquals("news_publish_unapproved", assertThrows(HeronBusinessException.class,
            () -> news.publish(pending.workId(), pending.versionId(), publish(pending))).getCode());

        news.review(pending.workId(), pending.versionId(), pending.approve());
        var wrongHash = new NewsPublishBo("https://www.douyin.com/video/7412345678901234567", "heron",
            LocalDateTime.now(clock), UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        assertEquals("news_publish_hash", assertThrows(HeronBusinessException.class,
            () -> news.publish(pending.workId(), pending.versionId(), wrongHash)).getCode());

        var future = new NewsPublishBo("https://www.douyin.com/video/7412345678901234567", "heron",
            LocalDateTime.now(clock).plusMinutes(5), pending.file("mp4").sha256());
        assertEquals("news_publish_time", assertThrows(HeronBusinessException.class,
            () -> news.publish(pending.workId(), pending.versionId(), future)).getCode());

        String path = "/heron/newsWork/" + pending.workId() + "/versions/" + pending.versionId() + "/publish";
        loginAs(OTHER);
        mvc.perform(post(path).contentType("application/json").content(json(pending))).andExpect(status().isNotFound());
        // 有跨用户范围但没有发布权：与「不是你的作品」同样返回真实 404，且不留发布事实
        grantPermissions(OTHER, "heron:newsWork:all", "heron:newsWork:query");
        mvc.perform(post(path).contentType("application/json").content(json(pending))).andExpect(status().isNotFound());
        assertEquals(0, publishRowCount(pending.workId()));
        loginAs(OWNER);
        mvc.perform(post(path).contentType("application/json").content("{\"workUrl\":\"https://evil.example.com/video/1\",\"account\":\"heron\","
            + "\"publishedAt\":\"" + LocalDateTime.now(clock).minusMinutes(1) + "\",\"videoSha256\":\"" + pending.file("mp4").sha256() + "\"}"))
            .andExpect(status().isBadRequest());
        // 缺发布时间：控制器不带 @Valid，400 必须由服务层给出而不是 NPE 变 500
        mvc.perform(post(path).contentType("application/json").content("{\"workUrl\":\"https://www.douyin.com/video/7412345678901234567\","
            + "\"account\":\"heron\",\"videoSha256\":\"" + pending.file("mp4").sha256() + "\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(0, publishRowCount(pending.workId()));
    }

    private NewsPublishBo publish(NewsMysqlTestSupport.Completed version) {
        return new NewsPublishBo("https://www.douyin.com/video/7412345678901234567", "heron-官方号",
            LocalDateTime.now(clock).minusMinutes(1), version.file("mp4").sha256());
    }

    private String json(NewsMysqlTestSupport.Completed version) {
        NewsPublishBo receipt = publish(version);
        return "{\"workUrl\":\"" + receipt.workUrl() + "\",\"account\":\"" + receipt.account()
            + "\",\"publishedAt\":\"" + receipt.publishedAt() + "\",\"videoSha256\":\"" + receipt.videoSha256() + "\"}";
    }

    private long firstOwner() {
        return OWNER;
    }

    private LocalDateTime publishedAtOf(long workId) {
        return jdbc.queryForObject("SELECT c.published_at FROM heron_news_candidate c"
            + " JOIN heron_news_work w ON w.candidate_id = c.candidate_id WHERE w.work_id = ?",
            LocalDateTime.class, workId);
    }

    private int publishRowCount(long workId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM heron_video_version WHERE work_id = ? AND publish_at IS NOT NULL",
            Integer.class, workId);
    }
}
