package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 新增适配器的解析口径：只测纯 build，不碰网络。 */
@DisplayName("数据看板：新增源适配器")
class DatasetAdaptersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-10T03:22:00Z");

    @Test
    @DisplayName("东财上证：日期与收盘价，取最近窗口")
    void eastmoneyIndex() {
        String body = "{\"data\":{\"klines\":[\"2026-11-07,4000.00,4010.50\",\"2026-11-10,4010.50,4020.00\"]}}";
        JsonNode node = JSON.readTree(EastmoneyIndexAdapter.build(EastmoneyIndexAdapter.ALL.get(0),
            body, "https://x.test/k", NOW, 30));
        assertEquals("eastmoney-sse", node.path("source_key").asString());
        assertEquals(2, node.path("rows").size());
        assertEquals(4020.00, node.path("rows").get(1).get(1).asDouble(), 0.001);
        assertEquals("2026-11-10", node.path("time_range").path("end").asString());
    }

    @Test
    @DisplayName("Frankfurter：按日期排序，四位小数")
    void frankfurter() {
        String body = "{\"rates\":{\"2026-11-10\":{\"CNY\":7.0002},\"2026-11-09\":{\"CNY\":7.0011}}}";
        JsonNode node = JSON.readTree(FrankfurterAdapter.build(FrankfurterAdapter.ALL.get(0),
            body, "https://x.test/fx", NOW, 30));
        assertEquals("frankfurter", node.path("source_key").asString());
        assertEquals("2026-11-09", node.path("time_range").path("start").asString());
        assertEquals(7.0002, node.path("rows").get(1).get(1).asDouble(), 0.00001);
        assertEquals(4, node.path("metric").path("precision").asInt());
    }

    @Test
    @DisplayName("东财 CPI：新到旧输入重排为升序，取同比")
    void eastmoneyCpi() {
        String body = "{\"result\":{\"data\":["
            + "{\"REPORT_DATE\":\"2026-10-01 00:00:00\",\"NATIONAL_SAME\":0.7},"
            + "{\"REPORT_DATE\":\"2026-09-01 00:00:00\",\"NATIONAL_SAME\":-0.3}]}}";
        JsonNode node = JSON.readTree(EastmoneyMacroAdapter.build(EastmoneyMacroAdapter.ALL.get(0),
            body, "https://x.test/cpi", NOW, 24));
        assertEquals(2, node.path("rows").size());
        assertEquals("2026-09-01", node.path("time_range").path("start").asString());
        assertEquals(0.7, node.path("rows").get(1).get(1).asDouble(), 0.001);
        assertEquals("monthly", node.path("cadence").asString());
    }

    @Test
    @DisplayName("MET Norway：按北京时间归日，取当天最高温")
    void metnoDailyMax() {
        String body = "{\"properties\":{\"timeseries\":["
            + "{\"time\":\"2026-11-10T00:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":5.0}}}},"
            + "{\"time\":\"2026-11-10T06:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":9.0}}}},"
            + "{\"time\":\"2026-11-11T00:00:00Z\",\"data\":{\"instant\":{\"details\":{\"air_temperature\":7.0}}}}]}}";
        JsonNode node = JSON.readTree(MetNoAdapter.build(MetNoAdapter.ALL.get(0),
            body, "https://x.test/w", NOW, 30));
        assertEquals(2, node.path("rows").size());
        assertEquals("2026-11-10", node.path("rows").get(0).get(0).asString());
        assertEquals(9.0, node.path("rows").get(0).get(1).asDouble(), 0.001);
        assertEquals(7.0, node.path("rows").get(1).get(1).asDouble(), 0.001);
    }

    @Test
    @DisplayName("USGS：按日补零计数，窗口内每天都有一行")
    void usgsDailyCounts() {
        long first = Instant.parse("2026-11-09T05:00:00Z").toEpochMilli();
        long second = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli();
        long third = Instant.parse("2026-11-10T03:00:00Z").toEpochMilli();
        String body = "{\"features\":[{\"properties\":{\"time\":" + first + "}},"
            + "{\"properties\":{\"time\":" + second + "}},{\"properties\":{\"time\":" + third + "}}]}";
        JsonNode node = JSON.readTree(UsgsEarthquakeAdapter.build(UsgsEarthquakeAdapter.ALL.get(0),
            body, "https://x.test/q", NOW, 3));
        assertEquals(3, node.path("rows").size());
        assertEquals("2026-11-08", node.path("rows").get(0).get(0).asString());
        assertEquals(0, node.path("rows").get(0).get(1).asInt());
        assertEquals(2, node.path("rows").get(2).get(1).asInt());
    }

    @Test
    @DisplayName("维基榜单：跳过首页，解码中文标题，输出 rows 形态")
    void wikimediaTop() {
        String body = "{\"items\":[{\"articles\":["
            + "{\"article\":\"Main_Page\",\"views\":100000},"
            + "{\"article\":\"%E4%B8%AD%E5%9B%BD\",\"views\":50000},"
            + "{\"article\":\"%E7%BE%8E%E5%9B%BD\",\"views\":40000}]}]}";
        JsonNode node = JSON.readTree(WikimediaTopAdapter.build(WikimediaTopAdapter.ALL.get(0),
            body, "https://x.test/w", NOW, 10));
        assertEquals("rows", node.path("shape").asString());
        assertEquals(2, node.path("rows").size());
        assertEquals("中国", node.path("rows").get(0).get(0).asString());
        assertEquals("中国", node.path("facts").path("leader").path("label").asString());
    }

    @Test
    @DisplayName("HN：按得分降序，取前 N 条")
    void hackerNewsTop() {
        String low = "{\"title\":\"Low story\",\"score\":10}";
        String high = "{\"title\":\"High story\",\"score\":900}";
        JsonNode node = JSON.readTree(HackerNewsAdapter.build(List.of(low, high), "https://x.test/hn", NOW, 10));
        assertEquals("rows", node.path("shape").asString());
        assertEquals(2, node.path("rows").size());
        assertEquals("High story", node.path("rows").get(0).get(0).asString());
        assertEquals(900, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("GitHub：按星标降序，取前 N 条")
    void githubTrending() {
        String body = "{\"items\":[{\"full_name\":\"a/low\",\"stargazers_count\":12},"
            + "{\"full_name\":\"b/high\",\"stargazers_count\":9000}]}";
        JsonNode node = JSON.readTree(GithubTrendingAdapter.build(GithubTrendingAdapter.ALL.get(0),
            body, "https://x.test/gh", NOW, 10));
        assertEquals(2, node.path("rows").size());
        assertEquals("b/high", node.path("rows").get(0).get(0).asString());
        assertEquals(9000, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("HuggingFace：按下载量降序")
    void huggingFace() {
        String body = "[{\"id\":\"a/small\",\"downloads\":10},{\"id\":\"b/big\",\"downloads\":9000}]";
        JsonNode node = JSON.readTree(HuggingFaceAdapter.build(HuggingFaceAdapter.ALL.get(0),
            body, "https://x.test/hf", NOW, 10));
        assertEquals("b/big", node.path("rows").get(0).get(0).asString());
        assertEquals(9000, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("Jikan：按成员数降序")
    void jikanTop() {
        String body = "{\"data\":[{\"title\":\"A\",\"members\":100},{\"title\":\"B\",\"members\":2000}]}";
        JsonNode node = JSON.readTree(JikanTopAdapter.build(JikanTopAdapter.ALL.get(0),
            body, "https://x.test/j", NOW, 10));
        assertEquals("B", node.path("rows").get(0).get(0).asString());
        assertEquals(2000, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("CheapShark：字符串折扣转数值并按降序")
    void cheapShark() {
        String body = "[{\"title\":\"Low\",\"savings\":\"10.5\"},{\"title\":\"High\",\"savings\":\"90.0\"}]";
        JsonNode node = JSON.readTree(CheapSharkAdapter.build(CheapSharkAdapter.ALL.get(0),
            body, "https://x.test/cs", NOW, 10));
        assertEquals("High", node.path("rows").get(0).get(0).asString());
        assertEquals(90.0, node.path("rows").get(0).get(1).asDouble(), 0.001);
    }

    @Test
    @DisplayName("世行：按指标缩放、降序取前 N")
    void worldBankRanking() {
        String body = "[{\"page\":1},["
            + "{\"country\":{\"value\":\"China\"},\"value\":18000000000000},"
            + "{\"country\":{\"value\":\"United States\"},\"value\":27000000000000}]]";
        JsonNode node = JSON.readTree(WorldBankRankingAdapter.build(
            WorldBankRankingAdapter.ALL.get(0), body, "https://x.test/wb", NOW, 10));
        assertEquals("United States", node.path("rows").get(0).get(0).asString());
        assertEquals(27000, node.path("rows").get(0).get(1).asInt());
        assertEquals("annual", node.path("cadence").asString());
    }

    @Test
    @DisplayName("Stack Overflow：按得分降序")
    void stackExchange() {
        String body = "{\"items\":[{\"title\":\"Low\",\"score\":1},{\"title\":\"High\",\"score\":99}]}";
        JsonNode node = JSON.readTree(StackExchangeAdapter.build(StackExchangeAdapter.ALL.get(0),
            body, "https://x.test/se", NOW, 10));
        assertEquals("High", node.path("rows").get(0).get(0).asString());
        assertEquals(99, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("DEV：按互动数降序")
    void devTo() {
        String body = "[{\"title\":\"Low\",\"public_reactions_count\":2},"
            + "{\"title\":\"High\",\"public_reactions_count\":80}]";
        JsonNode node = JSON.readTree(DevToAdapter.build(DevToAdapter.ALL.get(0),
            body, "https://x.test/dev", NOW, 10));
        assertEquals("High", node.path("rows").get(0).get(0).asString());
        assertEquals(80, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("东财 PMI：指数保留一位小数，标签与单位随指标切换")
    void eastmoneyPmi() {
        String body = "{\"result\":{\"data\":["
            + "{\"REPORT_DATE\":\"2026-10-01 00:00:00\",\"MAKE_INDEX\":49.3},"
            + "{\"REPORT_DATE\":\"2026-09-01 00:00:00\",\"MAKE_INDEX\":50.1}]}}";
        JsonNode node = JSON.readTree(EastmoneyMacroAdapter.build(EastmoneyMacroAdapter.ALL.get(2),
            body, "https://x.test/pmi", NOW, 24));
        assertEquals("制造业 PMI", node.path("metric").path("label").asString());
        assertEquals("指数", node.path("metric").path("unit").asString());
        assertEquals(49.3, node.path("rows").get(1).get(1).asDouble(), 0.001);
    }

    @Test
    @DisplayName("OWID：只取 World 行，年度涨到 YYYY-01-01，按精度缩放")
    void owidSeries() {
        String csv = "Entity,Code,Year,Life expectancy\r\n"
            + "World,OWID_WRL,1949,50\r\n"
            + "World,OWID_WRL,1950,51.5\r\n"
            + "World,OWID_WRL,1951,52.25\r\n"
            + "China,CHN,1951,45\r\n";
        JsonNode node = JSON.readTree(OwidSeriesAdapter.build(OwidSeriesAdapter.ALL.get(0),
            csv, "https://x.test/owid", NOW, 30));
        assertEquals(2, node.path("rows").size());
        assertEquals("1950-01-01", node.path("rows").get(0).get(0).asString());
        assertEquals(52.3, node.path("rows").get(1).get(1).asDouble(), 0.001);
        assertEquals("annual", node.path("cadence").asString());
    }

    @Test
    @DisplayName("Gapminder：按年均值，未加权")
    void gapminderFertility() {
        String body = "[{\"year\":1955,\"country\":\"A\",\"fertility\":6},"
            + "{\"year\":1955,\"country\":\"B\",\"fertility\":4},"
            + "{\"year\":2005,\"country\":\"A\",\"fertility\":3},"
            + "{\"year\":2005,\"country\":\"B\",\"fertility\":1}]";
        JsonNode node = JSON.readTree(GapminderFertilityAdapter.build(body, "https://x.test/gm", NOW, 30));
        assertEquals(2, node.path("rows").size());
        assertEquals(5.0, node.path("rows").get(0).get(1).asDouble(), 0.001);
        assertEquals(2.0, node.path("rows").get(1).get(1).asDouble(), 0.001);
        assertEquals("2005-01-01", node.path("time_range").path("end").asString());
    }

    @Test
    @DisplayName("IMDb 票房：按全球票房降序，换算百万美元")
    void vegaMovies() {
        String body = "[{\"Title\":\"Low\",\"Worldwide Gross\":1000000},"
            + "{\"Title\":\"High\",\"Worldwide Gross\":900000000}]";
        JsonNode node = JSON.readTree(VegaMoviesAdapter.build(body, "https://x.test/mv", NOW, 10));
        assertEquals("High", node.path("rows").get(0).get(0).asString());
        assertEquals(900, node.path("rows").get(0).get(1).asInt());
    }

    @Test
    @DisplayName("市值榜：美股/港股/A 股分别按汇率折人民币，降序取前 N")
    void eastmoneyMarketCap() {
        String body = "{\"data\":{\"diff\":["
            + "{\"f12\":\"AAPL\",\"f13\":105,\"f14\":\"苹果\",\"f20\":5000000000000},"
            + "{\"f12\":\"00700\",\"f13\":116,\"f14\":\"腾讯控股\",\"f20\":2000000000000},"
            + "{\"f12\":\"600519\",\"f13\":1,\"f14\":\"贵州茅台\",\"f20\":1000000000000}]}}";
        JsonNode node = JSON.readTree(EastmoneyMarketCapAdapter.build(
            body, 7.0, 0.9, "https://x.test/cap", NOW, 20));
        assertEquals("苹果", node.path("rows").get(0).get(0).asString());
        assertEquals(350000, node.path("rows").get(0).get(1).asInt());
        assertEquals(18000, node.path("rows").get(1).get(1).asInt());
        assertEquals(10000, node.path("rows").get(2).get(1).asInt());
    }

    @Test
    @DisplayName("OWID 赛跑：只留 ISO3 实体、输出 long、首尾年份对齐")
    void owidRace() {
        StringBuilder csv = new StringBuilder("Entity,Code,Year,Population\n");
        csv.append("World,OWID_WRL,2000,6000000000\n");
        csv.append("Asia,,2000,4000000000\n");
        for (int year = 1950; year <= 2023; year++) {
            csv.append("China,CHN,").append(year).append(',')
                .append(1_000_000_000L + (year - 1950) * 1_000_000L).append('\n');
            csv.append("India,IND,").append(year).append(',')
                .append(500_000_000L + (year - 1950) * 2_000_000L).append('\n');
            csv.append("Monaco,MCO,").append(year).append(",30000\n");
            csv.append("Atlantis,ATL,").append(year).append(",10000\n");
            csv.append("Hong Kong,HKG,").append(year).append(",7000000\n");
            csv.append("Taiwan,TWN,").append(year).append(",20000000\n");
        }
        JsonNode node = JSON.readTree(OwidRaceAdapter.build(OwidRaceAdapter.ALL.get(0),
            csv.toString(), "https://x.test/r", NOW));
        assertEquals("long", node.path("shape").asString());
        assertEquals("date", node.path("columns").get(0).asString());
        assertEquals("series", node.path("columns").get(1).asString());
        assertEquals("value", node.path("columns").get(2).asString());
        assertEquals("1950-01-01", node.path("time_range").path("start").asString());
        assertEquals("2023-01-01", node.path("time_range").path("end").asString());
        List<String> names = new ArrayList<>();
        node.path("rows").forEach(row -> names.add(row.get(1).asString()));
        assertFalse(names.contains("World"), "聚合行要按 ISO3 过滤掉");
        assertTrue(names.contains("中国"), "已收录的 ISO3 要翻成中文");
        assertTrue(names.contains("摩纳哥"));
        assertFalse(names.contains("China"));
        assertTrue(names.contains("Atlantis"), "未收录的 ISO3 回退英文名，不猜");
        assertFalse(names.contains("Hong Kong"), "港澳台实体直接剔除，不能靠翻译兜");
        assertFalse(names.contains("Taiwan"));
        assertEquals(4, names.stream().distinct().count());
        assertEquals(120, node.path("rows").size(), "4 个实体 × 30 个时点");
    }

    @Test
    @DisplayName("OWID 赛跑：年份等距抽稀，首尾都在")
    void raceSampleYears() {
        List<Integer> years = OwidRaceAdapter.sampleYears(1950, 2023);
        assertEquals(30, years.size());
        assertEquals(1950, years.get(0));
        assertEquals(2023, years.get(years.size() - 1));
    }

    @Test
    @DisplayName("适配器声明的形态都在契约里，且能映射到图表模块")
    void adapterShapesMatchContract() {
        List<DatasetAdapter> adapters = List.of(
            new SinaFuturesDatasetAdapter(SinaFuturesDatasetAdapter.GOLD),
            new EastmoneyMarketCapAdapter(),
            new OwidSeriesAdapter(OwidSeriesAdapter.ALL.get(0)),
            new OwidRaceAdapter(OwidRaceAdapter.ALL.get(0)));
        for (DatasetAdapter adapter : adapters) {
            assertFalse(DatasetScriptContract.chartFor(adapter.shape()).isBlank(),
                adapter.sourceKey() + " 声明的形态不在契约里：" + adapter.shape());
        }
    }

    @Test
    @DisplayName("空响应一律失败，不产出空快照")
    void rejectsEmpty() {
        assertThrows(HeronBusinessException.class, () -> EastmoneyIndexAdapter.build(
            EastmoneyIndexAdapter.ALL.get(0), "{}", "https://x.test/k", NOW, 30));
        assertThrows(HeronBusinessException.class, () -> FrankfurterAdapter.build(
            FrankfurterAdapter.ALL.get(0), "{}", "https://x.test/fx", NOW, 30));
        assertThrows(HeronBusinessException.class, () -> EastmoneyMacroAdapter.build(
            EastmoneyMacroAdapter.ALL.get(0), "{}", "https://x.test/cpi", NOW, 24));
        assertThrows(HeronBusinessException.class, () -> MetNoAdapter.build(
            MetNoAdapter.ALL.get(0), "{}", "https://x.test/w", NOW, 30));
        assertThrows(HeronBusinessException.class, () -> UsgsEarthquakeAdapter.build(
            UsgsEarthquakeAdapter.ALL.get(0), "{}", "https://x.test/q", NOW, 3));
        assertThrows(HeronBusinessException.class, () -> WikimediaTopAdapter.build(
            WikimediaTopAdapter.ALL.get(0), "{}", "https://x.test/w", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> HackerNewsAdapter.build(
            List.of(), "https://x.test/hn", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> GithubTrendingAdapter.build(
            GithubTrendingAdapter.ALL.get(0), "{}", "https://x.test/gh", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> HuggingFaceAdapter.build(
            HuggingFaceAdapter.ALL.get(0), "{}", "https://x.test/hf", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> JikanTopAdapter.build(
            JikanTopAdapter.ALL.get(0), "{}", "https://x.test/j", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> CheapSharkAdapter.build(
            CheapSharkAdapter.ALL.get(0), "{}", "https://x.test/cs", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> WorldBankRankingAdapter.build(
            WorldBankRankingAdapter.ALL.get(0), "{}", "https://x.test/wb", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> StackExchangeAdapter.build(
            StackExchangeAdapter.ALL.get(0), "{}", "https://x.test/se", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> DevToAdapter.build(
            DevToAdapter.ALL.get(0), "{}", "https://x.test/dev", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> OwidSeriesAdapter.build(
            OwidSeriesAdapter.ALL.get(0), "Entity,Code,Year,Life expectancy", "https://x.test/owid", NOW, 30));
        assertThrows(HeronBusinessException.class, () -> OwidRaceAdapter.build(
            OwidRaceAdapter.ALL.get(0), "{}", "https://x.test/r", NOW));
        assertThrows(HeronBusinessException.class, () -> GapminderFertilityAdapter.build(
            "[]", "https://x.test/gm", NOW, 30));
        assertThrows(HeronBusinessException.class, () -> VegaMoviesAdapter.build(
            "[]", "https://x.test/mv", NOW, 10));
        assertThrows(HeronBusinessException.class, () -> EastmoneyMarketCapAdapter.build(
            "{}", 7.0, 0.9, "https://x.test/cap", NOW, 20));
    }
}
