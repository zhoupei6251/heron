package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** story/1 契约闸：故事本身立不立得住，全靠这里钉死。 */
@DisplayName("数据看板：策划契约闸")
class DatasetStoryContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一份能过闸的规划快照：单指标时间序列，末值高于起点。 */
    private static JsonNode snapshot() {
        return JSON.readTree("""
            {
              "contract": "dataset/1",
              "source_key": "sina-gold-xau",
              "source_name": "黄金",
              "shape": "wide",
              "cadence": "daily",
              "rows": [
                ["2026-11-01", 100], ["2026-11-02", 98], ["2026-11-03", 102],
                ["2026-11-04", 105], ["2026-11-05", 108], ["2026-11-06", 110],
                ["2026-11-07", 112], ["2026-11-08", 115], ["2026-11-09", 121],
                ["2026-11-10", 120]
              ],
              "facts": {
                "first": {"date": "2026-11-01", "value": 100},
                "last": {"date": "2026-11-10", "value": 120},
                "min": {"date": "2026-11-02", "value": 98},
                "max": {"date": "2026-11-09", "value": 121},
                "change": 20,
                "change_pct": 0.2,
                "period": {"start_year": 2026, "end_year": 2026}
              },
              "data_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """);
    }

    /** 一份能过闸的故事：数字反差，主张两条事实加一条假设。 */
    private static EditorialStory story() {
        return new EditorialStory(
            "story/1",
            "numeric_contrast",
            "金价十天涨了两次",
            new EditorialStory.Protagonist("metric", "黄金价格", null),
            new EditorialStory.Tension("gap", "末值与区间峰值只差一点",
                List.of("facts.last.value", "facts.max.value")),
            List.of(
                new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact",
                    List.of("facts.last.value")),
                new EditorialStory.Claim("c2", "区间里的峰值出现在靠后一段", "fact",
                    List.of("facts.max.value")),
                new EditorialStory.Claim("c3", "后续可能回落到均值附近", "assumption", List.of())),
            new EditorialStory.Payoff("差距比看上去更小", List.of("facts.last.value")),
            new EditorialStory.Stakes("持有黄金的账户", "成本线比想象中更靠近现价"),
            List.of("后续可能回落到均值附近"),
            new EditorialStory.Basis("opportunity", 0.2, 1.5, false, 1.0, 10,
                "2026-11-01", "2026-11-10", "a".repeat(64), "opportunity", 1L));
    }

    private static EditorialStory withClaims(List<EditorialStory.Claim> claims,
                                             List<String> assumptions) {
        EditorialStory base = story();
        return new EditorialStory(base.schemaVersion(), base.narrative(), base.oneLine(),
            base.protagonist(), base.tension(), claims, base.payoff(), base.stakes(),
            assumptions, base.basis());
    }

    @Test
    @DisplayName("合规的故事没有违例")
    void validStoryPasses() {
        assertEquals(List.of(), DatasetStoryContract.violations(story(), snapshot()));
    }

    @Test
    @DisplayName("文本里写裸数字直接点名字段")
    void bareDigitRejected() {
        EditorialStory base = story();
        EditorialStory broken = new EditorialStory(base.schemaVersion(), base.narrative(),
            "金价涨了 20 美元", base.protagonist(), base.tension(), base.claims(),
            base.payoff(), base.stakes(), base.assumptions(), base.basis());
        List<String> items = DatasetStoryContract.violations(broken, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("oneLine") && item.contains("裸数字")),
            items.toString());
    }

    @Test
    @DisplayName("事实主张没有出处就拒")
    void factWithoutEvidenceRejected() {
        List<EditorialStory.Claim> claims = new ArrayList<>(story().claims());
        claims.set(0, new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact", List.of()));
        List<String> items = DatasetStoryContract.violations(withClaims(claims, List.of()), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("c1.evidenceRefs")), items.toString());
    }

    @Test
    @DisplayName("假设带出处就拒")
    void assumptionWithEvidenceRejected() {
        List<EditorialStory.Claim> claims = new ArrayList<>(story().claims());
        claims.set(2, new EditorialStory.Claim("c3", "后续可能回落到均值附近", "assumption",
            List.of("facts.max.value")));
        List<String> items = DatasetStoryContract.violations(withClaims(claims,
            List.of("后续可能回落到均值附近")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("不该带 evidenceRefs")), items.toString());
    }

    @Test
    @DisplayName("假设清单与主张里的假设对不上就拒")
    void assumptionMismatchRejected() {
        List<String> items = DatasetStoryContract.violations(
            withClaims(story().claims(), List.of("另一句假设")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("一一对应")), items.toString());
    }

    @Test
    @DisplayName("叙事与张力不相容就拒")
    void incompatibleTensionRejected() {
        EditorialStory base = story();
        EditorialStory broken = new EditorialStory(base.schemaVersion(), base.narrative(),
            base.oneLine(), base.protagonist(),
            new EditorialStory.Tension("reversal", base.tension().statement(), base.tension().evidenceRefs()),
            base.claims(), base.payoff(), base.stakes(), base.assumptions(), base.basis());
        List<String> items = DatasetStoryContract.violations(broken, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("不相容")), items.toString());
    }

    @Test
    @DisplayName("事实主张里断言因果就拒")
    void causalFactRejected() {
        List<EditorialStory.Claim> claims = new ArrayList<>(story().claims());
        claims.set(0, new EditorialStory.Claim("c1", "因为避险需求上升，区间末值高于起点", "fact",
            List.of("facts.last.value")));
        List<String> items = DatasetStoryContract.violations(withClaims(claims, List.of()), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("不能断言因果")), items.toString());
    }

    @Test
    @DisplayName("事实主张不足两条就拒")
    void tooFewFactsRejected() {
        List<EditorialStory.Claim> claims = List.of(
            new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact", List.of("facts.last.value")),
            new EditorialStory.Claim("c2", "后续可能回落到均值附近", "assumption", List.of()));
        List<String> items = DatasetStoryContract.violations(withClaims(claims,
            List.of("后续可能回落到均值附近")), snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("事实主张不足")), items.toString());
    }

    @Test
    @DisplayName("方向与数据相反就拒")
    void directionMismatchRejected() {
        EditorialStory base = story();
        EditorialStory broken = new EditorialStory(base.schemaVersion(), base.narrative(),
            base.oneLine(), base.protagonist(),
            new EditorialStory.Tension("gap", "末值跌回起点附近", base.tension().evidenceRefs()),
            base.claims(), base.payoff(), base.stakes(), base.assumptions(), base.basis());
        List<String> items = DatasetStoryContract.violations(broken, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("方向与数据不符")), items.toString());
    }

    @Test
    @DisplayName("绝对词没有单期满覆盖支撑就拒")
    void absoluteWordRejected() {
        EditorialStory base = story();
        EditorialStory broken = new EditorialStory(base.schemaVersion(), base.narrative(),
            base.oneLine(), base.protagonist(),
            new EditorialStory.Tension("gap", "这是区间内的最高点", base.tension().evidenceRefs()),
            base.claims(), base.payoff(), base.stakes(), base.assumptions(), base.basis());
        List<String> items = DatasetStoryContract.violations(broken, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("绝对词")), items.toString());
    }

    @Test
    @DisplayName("策划哈希必须是完整的 SHA-256")
    void planningHashRequired() {
        EditorialStory base = story();
        EditorialStory broken = new EditorialStory(base.schemaVersion(), base.narrative(),
            base.oneLine(), base.protagonist(), base.tension(), base.claims(), base.payoff(),
            base.stakes(), base.assumptions(),
            new EditorialStory.Basis("opportunity", 0.2, 1.5, false, 1.0, 10,
                "2026-11-01", "2026-11-10", "abc", "opportunity", 1L));
        List<String> items = DatasetStoryContract.violations(broken, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("planningDataSha256")), items.toString());
    }

    @Test
    @DisplayName("引用解析不到就拒，并点名是哪一条")
    void unresolvedRefRejected() {
        EditorialStory base = story();
        EditorialStory broken = new EditorialStory(base.schemaVersion(), base.narrative(),
            base.oneLine(), base.protagonist(),
            new EditorialStory.Tension("gap", base.tension().statement(),
                List.of("facts.last.value", "facts.nothing.here")),
            base.claims(), base.payoff(), base.stakes(), base.assumptions(), base.basis());
        List<String> items = DatasetStoryContract.violations(broken, snapshot());
        assertTrue(items.stream().anyMatch(item -> item.contains("facts.nothing.here")), items.toString());
    }

    @Test
    @DisplayName("生产快照方向翻转就拒")
    void productionDirectionFlipRejected() {
        JsonNode production = JSON.readTree("""
            {
              "shape": "wide",
              "rows": [["2026-11-01", 100], ["2026-11-10", 120]],
              "facts": {
                "first": {"date": "2026-11-01", "value": 100},
                "last": {"date": "2026-11-10", "value": 80},
                "min": {"date": "2026-11-01", "value": 80},
                "max": {"date": "2026-11-01", "value": 100},
                "change": -20,
                "change_pct": -0.2,
                "period": {"start_year": 2026, "end_year": 2026}
              },
              "data_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """);
        List<String> items = DatasetStoryContract.evidenceViolations(story(), production);
        assertTrue(items.stream().anyMatch(item -> item.contains("方向")), items.toString());
    }

    @Test
    @DisplayName("生产快照覆盖塌陷就拒")
    void productionCoverageCollapseRejected() {
        JsonNode production = JSON.readTree("""
            {
              "shape": "wide",
              "rows": [["2026-11-10", 120]],
              "facts": {
                "last": {"date": "2026-11-10", "value": 120},
                "max": {"date": "2026-11-09", "value": 121},
                "change_pct": 0.2
              },
              "data_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """);
        List<String> items = DatasetStoryContract.evidenceViolations(story(), production);
        assertTrue(items.stream().anyMatch(item -> item.contains("覆盖塌陷")), items.toString());
    }

    @Test
    @DisplayName("生产快照解析不到引用就拒，并带序号")
    void productionUnresolvedRefRejected() {
        JsonNode production = JSON.readTree("""
            {
              "shape": "wide",
              "rows": [["2026-11-10", 120]],
              "facts": {
                "last": {"date": "2026-11-10", "value": 120},
                "change_pct": 0.2
              },
              "data_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
            """);
        List<String> items = DatasetStoryContract.evidenceViolations(story(), production);
        assertTrue(items.stream().anyMatch(item -> item.contains("evidenceRef")
            && item.contains("生产快照里解析不到")), items.toString());
    }

    @Test
    @DisplayName("钩子表按叙事类型给出，不相容的钩子拿不到")
    void titleKindsFollowNarrative() {
        assertEquals(DatasetStoryContract.TITLE_KINDS.get("numeric_contrast"),
            DatasetStoryContract.titleKinds("numeric_contrast"));
        assertTrue(DatasetStoryContract.titleKinds("not_a_narrative").isEmpty());
    }
}
