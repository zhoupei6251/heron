package org.dromara.heron.service;

import static org.junit.jupiter.api.Assertions.*;

import cn.hutool.core.util.IdUtil;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.bo.NewsSourceBo;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsSourceMapper;
import org.dromara.heron.service.impl.NewsCandidateCollector;
import org.dromara.heron.service.impl.NewsFeedItem;
import org.dromara.heron.support.HeronNewsTestFixtures;
import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** N2 采集去重、来源租约与退避；全部跑在授权隔离 MySQL 上。 */
@DisplayName("N2 新闻来源采集去重与租约")
class NewsDeduplicationTest extends NewsMysqlTestSupport {

    private static final String SOURCE_URL = "https://feed.example.test/feed?utm_source=x&keep=1";

    @Autowired private NewsCandidateCollector collector;
    @Autowired private HeronNewsSourceMapper sources;
    @Autowired private HeronNewsCandidateMapper candidates;
    @Autowired private HeronNewsTestFixtures fixtures;
    @Autowired private INewsSourceService sourcesService;

    /** 夹具与库表都按用例重建，脚本状态不能跨用例残留。 */
    @BeforeEach
    void resetFixtures() {
        fixtures.reset();
    }

    private long newSource(String name, String host) {
        HeronNewsSource source = new HeronNewsSource();
        source.setSourceId(IdUtil.getSnowflakeNextId());
        source.setName(name);
        source.setSourceType("rss");
        source.setUrl("https://" + host + "/feed?utm_source=x&keep=1");
        source.setIntervalSeconds(600);
        source.setEnabled("0");
        source.setFailureCount(0);
        source.setCreateBy(OWNER);
        source.setUpdateBy(OWNER);
        assertEquals(1, sources.insert(source));
        return source.getSourceId();
    }

    private String claim(long sourceId) {
        String token = UUID.randomUUID().toString();
        assertEquals(1, sources.claim(sourceId, token, now(), now().plusSeconds(60)), "来源租约应被本令牌取得");
        return token;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private NewsFeedItem item(String title, String summary, String url, String itemId, int rank) {
        return new NewsFeedItem(title, summary, url, itemId, rank);
    }

    private List<HeronNewsCandidate> rows(String normalizedPrefix) {
        return candidates.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
            .like(HeronNewsCandidate::getNormalizedTitle, normalizedPrefix)
            .orderByAsc(HeronNewsCandidate::getCandidateId).build());
    }

    private int evidenceCount(long candidateId) {
        Map<String, Object> parsed = JsonUtils.parseMap(jdbc.queryForObject(
            "SELECT evidence FROM heron_news_candidate WHERE candidate_id = ?", String.class, candidateId));
        return parsed == null ? 0 : ((List<?>) parsed.get("items")).size();
    }

    private int number(long sourceId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM heron_news_source WHERE source_id = ?",
            Integer.class, sourceId);
    }

    private String text(long sourceId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM heron_news_source WHERE source_id = ?",
            String.class, sourceId);
    }

    private LocalDateTime time(long sourceId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM heron_news_source WHERE source_id = ?",
            LocalDateTime.class, sourceId);
    }

    private int countSources(String where, Object... params) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM heron_news_source WHERE " + where, Integer.class, params);
    }

    private NewsSourceBo bo(String name, String url) {
        NewsSourceBo bo = new NewsSourceBo();
        bo.setName(name);
        bo.setSourceType("rss");
        bo.setUrl(url);
        bo.setIntervalSeconds(600);
        bo.setEnabled("0");
        return bo;
    }

    @Test
    void twoSourcesWithSameNewsMergeAndKeepBothEvidence() {
        long a = newSource("来源甲", "feed.example.test");
        long b = newSource("来源乙", "other.example.test");
        collector.collect(a, claim(a), List.of(item("某地 发布 新规", "同一条新闻的原始摘要",
            "https://feed.example.test/a1?utm_source=rss&keep=1", "feed-a1", 2)));
        collector.collect(b, claim(b), List.of(item("某地发布新规！", "同一新闻的另一来源写法",
            "https://other.example.test/b1?fbclid=xyz", "atom-b1", 1)));

        List<HeronNewsCandidate> merged = rows("某地发布新规");
        assertEquals(1, merged.size(), "标点与空白差异必须合并为一条候选");
        HeronNewsCandidate candidate = merged.get(0);
        assertEquals(2L, candidate.getHitCount());
        assertEquals(2L, candidate.getHeatScore());
        assertEquals(1, candidate.getBestRank(), "热度取更好的排名");
        assertEquals(2, evidenceCount(candidate.getCandidateId()), "两个来源的证据都要保留");
    }

    @Test
    void numericAnchorDifferenceKeepsSeparateCandidates() {
        long a = newSource("数字来源", "feed.example.test");
        collector.collect(a, claim(a), List.of(
            item("某地发布新规 12.5 条", "第一条数字锚点", "https://feed.example.test/n1", "n1", 1),
            item("某地发布新规 12.6 条", "第二条数字锚点", "https://feed.example.test/n2", "n2", 2)));
        assertEquals(2, rows("某地发布新规").size(), "数字不同不能因为词面高度相似而误合并");
    }

    @Test
    void repeatedPollOfSameItemDoesNotGrowHitOrHeat() {
        long a = newSource("重复来源", "feed.example.test");
        List<NewsFeedItem> batch = List.of(item("稳定标题测试新闻", "同一来源的同一内容",
            "https://feed.example.test/r1", "r1", 1));
        collector.collect(a, claim(a), batch);
        HeronNewsCandidate first = rows("稳定标题测试新闻").get(0);
        collector.collect(a, claim(a), batch);
        HeronNewsCandidate second = rows("稳定标题测试新闻").get(0);
        assertEquals(first.getCandidateId(), second.getCandidateId());
        assertEquals(1L, second.getHitCount(), "重复轮询同一 sourceItemId 不增命中");
        assertEquals(1L, second.getHeatScore(), "重复轮询同一 sourceItemId 不增热度");
        assertEquals(1, evidenceCount(second.getCandidateId()), "重复轮询不重复追加证据");
    }

    @Test
    void canonicalTrackingAndFragmentShareOneKeyButSemanticQueryDoesNot() {
        long a = newSource("链接归一", "feed.example.test");
        collector.collect(a, claim(a), List.of(
            item("同一链接甲", "带跟踪参数的链接", "https://feed.example.test/a?utm_source=x&keep=1#frag", "u1", 1),
            item("同一链接乙", "清洗后的同一链接", "https://feed.example.test/a?keep=1", "u2", 2)));
        assertEquals(1, rows("同一链接").size(), "跟踪参数与 fragment 不参与精确键");
        collector.collect(a, claim(a), List.of(item("同一链接丙", "语义 query 不同",
            "https://feed.example.test/a?keep=2", "u3", 3)));
        assertEquals(2, rows("同一链接").size(), "语义 query 必须保持独立精确键");
    }

    @Test
    void concurrentCollectorsStoreOneCandidate() throws Exception {
        long a = newSource("并发来源甲", "feed.example.test");
        long b = newSource("并发来源乙", "other.example.test");
        String tokenA = claim(a);
        String tokenB = claim(b);
        var barrier = new CyclicBarrier(2);
        List<Runnable> tasks = List.of(
            () -> collector.collect(a, tokenA, List.of(item("并发唯一候选新闻", "甲来源",
                "https://feed.example.test/c1", "c1", 1))),
            () -> collector.collect(b, tokenB, List.of(item("并发唯一候选新闻！", "乙来源",
                "https://other.example.test/c2", "c2", 2))));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = tasks.stream().map(task -> pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                task.run();
                return Boolean.TRUE;
            })).toList();
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("并发采集不应失败", e);
        }
        assertEquals(1, rows("并发唯一候选新闻").size(), "生产线锁须让两个采集器串行合并");
        assertEquals(2L, rows("并发唯一候选新闻").get(0).getHitCount());
    }

    @Test
    void publishedCandidateIsNotResurrectedByCollection() {
        long a = newSource("已发布来源", "feed.example.test");
        collector.collect(a, claim(a), List.of(item("已发布新闻不再生产", "首发内容",
            "https://feed.example.test/p1", "p1", 1)));
        HeronNewsCandidate candidate = rows("已发布新闻不再生产").get(0);
        jdbc.update("UPDATE heron_news_candidate SET published_at = ? WHERE candidate_id = ?",
            now().minusDays(1), candidate.getCandidateId());

        collector.collect(a, claim(a), List.of(item("已发布新闻不再生产", "同一链接换了内容",
            "https://feed.example.test/p1", "p1b", 3)));

        List<HeronNewsCandidate> after = rows("已发布新闻不再生产");
        assertEquals(1, after.size(), "精确键命中已发布候选时不得新增候选");
        assertEquals(1L, after.get(0).getHitCount(), "已发布候选不得被重新累计热度");
        assertEquals(1, evidenceCount(after.get(0).getCandidateId()), "已发布候选不得追加证据");
    }

    @Test
    void staleTokenCannotWriteCandidatesOrSuccessFacts() {
        long a = newSource("陈旧令牌", "feed.example.test");
        claim(a);
        jdbc.update("UPDATE heron_news_source SET fetch_token = ? WHERE source_id = ?", "fresh-token", a);
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> collector.collect(a, "stale-token", List.of(item("陈旧令牌不得写入", "内容",
                "https://feed.example.test/s1", "s1", 1))));
        assertEquals("news_stale_token", e.getCode());
        assertTrue(rows("陈旧令牌不得写入").isEmpty());
        assertNull(time(a, "last_success_at"));
    }

    @Test
    void successfulCollectFinalizesOnlyForCurrentToken() {
        long a = newSource("定稿令牌", "feed.example.test");
        collector.collect(a, claim(a), List.of(item("定稿事实测试", "内容",
            "https://feed.example.test/f1", "f1", 1)));
        assertNull(text(a, "fetch_token"), "成功定稿后释放令牌");
        assertNotNull(time(a, "last_success_at"));
        assertEquals(0, number(a, "failure_count"));
        assertNull(text(a, "error_code"));
        assertEquals(now().plusSeconds(600), time(a, "next_fetch_at"), "成功按配置周期排下一次");
    }

    @Test
    void failureBackoffFollowsOneFiveFifteenThenConfiguredInterval() {
        long a = newSource("退避来源", "feed.example.test");
        fixtures.fail(SOURCE_URL);
        List<LocalDateTime> nextFetch = new ArrayList<>();
        for (int attempt = 1; attempt <= 4; attempt++) {
            HeronBusinessException e = assertThrows(HeronBusinessException.class, () -> sourcesService.fetch(a),
                "手动采集应把失败如实抛出");
            assertEquals("news_source_unreachable", e.getCode());
            assertEquals(attempt, number(a, "failure_count"));
            assertEquals("news_source_unreachable", text(a, "error_code"));
            assertNull(text(a, "fetch_token"), "失败也必须释放令牌");
            nextFetch.add(time(a, "next_fetch_at"));
        }
        assertEquals(now().plusMinutes(1), nextFetch.get(0));
        assertEquals(now().plusMinutes(5), nextFetch.get(1));
        assertEquals(now().plusMinutes(15), nextFetch.get(2));
        assertEquals(now().plusSeconds(600), nextFetch.get(3));
    }

    @Test
    void manualFetchIsRejectedWhileAnotherTokenIsLive() {
        long a = newSource("占用来源", "feed.example.test");
        claim(a);
        HeronBusinessException e = assertThrows(HeronBusinessException.class, () -> sourcesService.fetch(a));
        assertEquals("news_fetch_running", e.getCode());
        assertEquals(0, fixtures.connections(), "占用期间不得重复出网");
    }

    @Test
    void dueSchedulingPicksOnlyExpiredSourcesAndAtMostTwo() {
        for (int i = 1; i <= 5; i++) {
            long id = newSource("到期来源" + i, "feed" + i + ".example.test");
            jdbc.update("UPDATE heron_news_source SET next_fetch_at = ? WHERE source_id = ?",
                now().minusSeconds(1), id);
        }
        long future = newSource("未到期来源", "feed6.example.test");
        jdbc.update("UPDATE heron_news_source SET next_fetch_at = ? WHERE source_id = ?", now().plusMinutes(5), future);
        long disabled = newSource("停用来源", "feed7.example.test");
        jdbc.update("UPDATE heron_news_source SET next_fetch_at = ?, enabled = '1' WHERE source_id = ?",
            now().minusSeconds(1), disabled);
        fixtures.replyAll("<rss/>");

        assertEquals(2, sourcesService.collectDue(), "一轮最多领取两个到期来源");
        assertEquals(2, countSources("last_attempt_at IS NOT NULL"));
        assertEquals(2, fixtures.connections(), "两个到期来源各出网一次");
        assertEquals(0, countSources("source_id = ? AND last_attempt_at IS NOT NULL", disabled), "停用来源永不出网");
        assertEquals(0, countSources("source_id = ? AND last_attempt_at IS NOT NULL", future), "未到期来源不采集");
        assertEquals(3, countSources("enabled = '0' AND next_fetch_at > ?", now().plusSeconds(1)),
            "两个已采集的排到下个周期，未到期保持原计划");
    }

    @Test
    void sourceWithUnconfiguredAllowListNeverLeavesTheProcess() {
        long a = newSource("白名单外", "not-allowed.example.test");
        HeronBusinessException e = assertThrows(HeronBusinessException.class, () -> sourcesService.fetch(a));
        assertEquals("news_source_host", e.getCode());
        assertEquals(0, fixtures.connections());
        assertEquals("news_source_host", text(a, "error_code"), "失败原因须带真实错误码入库");
    }

    @Test
    void ipLiteralSourceUrlIsRejectedWhenSaved() {
        HeronBusinessException e = assertThrows(HeronBusinessException.class,
            () -> sourcesService.add(bo("字面 IP 来源", "https://10.0.0.8/feed")));
        assertEquals("news_source_url", e.getCode());
        assertEquals(0, countSources("name = '字面 IP 来源'"), "被拒绝的地址不得落库");
    }

    @Test
    void savedSourceUrlIsNormalizedBeforeItIsStored() {
        sourcesService.add(bo("归一来源", "https://feed.example.test:443/rss?keep=1#frag"));
        assertEquals("https://feed.example.test/rss?keep=1", text(nameOf("归一来源"), "url"),
            "默认端口与 fragment 不入库");
    }

    /** 由服务生成的雪花 id 不可预知，按名称回查主键。 */
    private long nameOf(String name) {
        return jdbc.queryForObject("SELECT source_id FROM heron_news_source WHERE name = ?", Long.class, name);
    }
}
