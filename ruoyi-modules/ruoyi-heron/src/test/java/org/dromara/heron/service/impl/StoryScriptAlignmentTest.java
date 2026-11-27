package org.dromara.heron.service.impl;

import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.service.impl.shared.production.StoryScriptAlignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 脚本和策划结论的对位：位置不对、路径越界都要拒。 */
@DisplayName("数据看板：脚本与策划对位")
class StoryScriptAlignmentTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static EditorialStory story() {
        return new EditorialStory(
            "story/1",
            "numeric_contrast",
            "末值与峰值只差一点",
            new EditorialStory.Protagonist("metric", "黄金价格", null),
            new EditorialStory.Tension("gap", "末值与区间峰值只差一点",
                List.of("facts.last.value", "facts.max.value")),
            List.of(
                new EditorialStory.Claim("c1", "区间末值高于区间起点", "fact",
                    List.of("facts.last.value")),
                new EditorialStory.Claim("c2", "区间里的峰值出现在靠后一段", "fact",
                    List.of("facts.max.value"))),
            new EditorialStory.Payoff("差距比看上去更小", List.of("facts.last.value")),
            new EditorialStory.Stakes("持有黄金的账户", "成本线比想象中更靠近现价"),
            List.of(),
            new EditorialStory.Basis("opportunity", 0.2, 1.5, false, 1.0, 10,
                "2026-11-01", "2026-11-10", "a".repeat(64), "opportunity", 1L));
    }

    /** 对得上的脚本：开场讲张力，中段两条主张各一段，结尾讲落点。 */
    private static JsonNode script() {
        return JSON.readTree("""
            {
              "title": "{{facts.last.value}} 附近的差距",
              "scenes": [
                {"lead": "开场讲张力：{{facts.last.value}}", "body": "先看这个"},
                {"lead": "第一条主张：{{facts.last.value}}", "body": "中间讲法一"},
                {"lead": "第二条主张：{{facts.max.value}}", "body": "中间讲法二"},
                {"lead": "补充一句", "body": "不引数字"},
                {"lead": "收尾铺垫", "body": "不引数字"}
              ],
              "closing": "结尾讲落点：{{facts.last.value}}"
            }
            """);
    }

    @Test
    @DisplayName("对得上的脚本没有违例")
    void alignedScriptPasses() {
        assertEquals(List.of(),
            StoryScriptAlignment.violations(script(), story(), script().path("title").asString("")));
    }

    @Test
    @DisplayName("没有策划结论就不做对位校验")
    void noStoryNoCheck() {
        assertEquals(List.of(), StoryScriptAlignment.violations(script(), null, "标题"));
    }

    @Test
    @DisplayName("开场引用张力之外的路径就拒")
    void openingOutsideTensionRejected() {
        JsonNode broken = JSON.readTree("""
            {
              "title": "标题",
              "scenes": [
                {"lead": "开场：{{facts.min.value}}", "body": ""},
                {"lead": "{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.max.value}}", "body": ""},
                {"lead": "补充", "body": ""}
              ],
              "closing": "{{facts.last.value}}"
            }
            """);
        List<String> items = StoryScriptAlignment.violations(broken, story(), "标题");
        assertTrue(items.stream().anyMatch(item -> item.contains("开场引用了张力之外的路径")
            && item.contains("facts.min.value")), items.toString());
    }

    @Test
    @DisplayName("中段段数讲不完事实主张就拒")
    void tooFewMiddleScenesRejected() {
        JsonNode broken = JSON.readTree("""
            {
              "title": "标题",
              "scenes": [
                {"lead": "开场：{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.last.value}}", "body": ""},
                {"lead": "补充", "body": ""}
              ],
              "closing": "{{facts.last.value}}"
            }
            """);
        List<String> items = StoryScriptAlignment.violations(broken, story(), "标题");
        assertTrue(items.stream().anyMatch(item -> item.contains("中段只有")), items.toString());
    }

    @Test
    @DisplayName("事实主张的出处没在中段出现就拒")
    void claimEvidenceMissingRejected() {
        JsonNode broken = JSON.readTree("""
            {
              "title": "标题",
              "scenes": [
                {"lead": "开场：{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.last.value}}", "body": ""},
                {"lead": "没有引用", "body": ""},
                {"lead": "补充", "body": ""}
              ],
              "closing": "{{facts.last.value}}"
            }
            """);
        List<String> items = StoryScriptAlignment.violations(broken, story(), "标题");
        assertTrue(items.stream().anyMatch(item -> item.contains("c2")
            && item.contains("没有出现在中段")), items.toString());
    }

    @Test
    @DisplayName("结尾不引用落点路径就拒")
    void closingWithoutPayoffRejected() {
        JsonNode broken = JSON.readTree("""
            {
              "title": "标题",
              "scenes": [
                {"lead": "开场：{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.max.value}}", "body": ""},
                {"lead": "补充", "body": ""}
              ],
              "closing": "结尾不引数字"
            }
            """);
        List<String> items = StoryScriptAlignment.violations(broken, story(), "标题");
        assertTrue(items.stream().anyMatch(item -> item.contains("结尾没有引用落点")), items.toString());
    }

    @Test
    @DisplayName("正文引用信封之外的路径就拒")
    void pathOutsideEnvelopeRejected() {
        JsonNode broken = JSON.readTree("""
            {
              "title": "标题",
              "scenes": [
                {"lead": "开场：{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.last.value}}", "body": ""},
                {"lead": "{{facts.max.value}}", "body": ""},
                {"lead": "{{facts.first.value}}", "body": ""}
              ],
              "closing": "{{facts.last.value}}"
            }
            """);
        List<String> items = StoryScriptAlignment.violations(broken, story(), "标题");
        assertTrue(items.stream().anyMatch(item -> item.contains("故事信封之外的路径")
            && item.contains("facts.first.value")), items.toString());
    }
}
