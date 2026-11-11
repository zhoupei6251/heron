package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 占位符解析：数字可朗读、年份取整、缺路径直接失败。 */
@DisplayName("数据看板：占位符解析")
class DatasetPlaceholdersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode dataset() {
        String body = "/*x*/ var _hf_XAU=(["
            + "{\"date\":\"2026-11-05\",\"close\":\"4091.55\"},"
            + "{\"date\":\"2026-11-06\",\"close\":\"4105.50\"},"
            + "{\"date\":\"2026-11-09\",\"close\":\"4110.04\"},"
            + "{\"date\":\"2026-11-10\",\"close\":\"4113.34\"}]);";
        return JSON.readTree(SinaFuturesDatasetAdapter.build(SinaFuturesDatasetAdapter.GOLD, body,
            Instant.parse("2026-11-10T03:22:00Z"), 30));
    }

    @Test
    @DisplayName("值、变化、百分比按各自精度替换；年份取整")
    void fillsNumbers() {
        String text = DatasetPlaceholders.fill(
            "收于 {{facts.last.value}} 美元，涨 {{facts.change}} 美元，涨幅 {{facts.change_pct}}%，起始 {{facts.period.start_year}} 年。",
            dataset(), 2);
        assertEquals("收于 4113.34 美元，涨 21.79 美元，涨幅 0.53%，起始 2026 年。", text);
    }

    @Test
    @DisplayName("整份脚本解析后标题、段落、结尾都替换，原稿不变")
    void resolvesScriptCopy() {
        JsonNode snapshot = dataset();
        JsonNode script = JSON.readTree("{\"title\":\"金价 {{facts.last.value}}\",\"scenes\":["
            + "{\"lead\":\"站上关键位\",\"body\":\"收于 {{facts.last.value}} 美元。\",\"duration_s\":15}],"
            + "\"closing\":\"区间最高 {{facts.max.value}} 美元。\",\"source_name\":\"来源：新浪财经\"}");
        JsonNode resolved = DatasetPlaceholders.resolve(script, snapshot);
        assertEquals("金价 4113.34", resolved.path("title").asString());
        assertEquals("收于 4113.34 美元。", resolved.path("scenes").get(0).path("body").asString());
        assertEquals("区间最高 4113.34 美元。", resolved.path("closing").asString());
        assertTrue(script.path("title").asString().contains("{{"), "原稿必须保持占位符供闸复核");
    }

    @Test
    @DisplayName("路径不存在直接失败，不留下花括号")
    void rejectsMissingPath() {
        assertThrows(HeronBusinessException.class,
            () -> DatasetPlaceholders.fill("收于 {{facts.last.price}} 美元", dataset(), 2));
    }
}
