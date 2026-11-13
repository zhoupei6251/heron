package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据信号：窗口怎么取、异常怎么判、没信号时不能硬造。
 * 阈值要拿真实数据校准，所以这里的断言只钉口径，不钉具体阈值。
 */
@DisplayName("数据信号：窗口、偏离与不硬造")
class DatasetSignalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int WINDOW = 120;
    /** 夹具日期都落在 2026-01，基准月固定，避免测试随真实时间漂。 */
    private static final java.time.YearMonth TODAY = java.time.YearMonth.of(2026, 1);
    /** 价格/指数类：百分比有意义，大变化基准 5%。 */
    private static final DatasetSignal.CategoryPolicy FINANCE = DatasetSignal.CategoryPolicy.of("finance");

    private static JsonNode snapshot(String shape, String cadence, String rowsJson) {
        return JSON.readTree("""
            {"shape":"%s","cadence":"%s","metric":{"key":"price"},
             "rows":%s}
            """.formatted(shape, cadence, rowsJson));
    }

    /** 生成 [[date, value]] 序列；日期定宽，保证字符串排序与时间序一致。 */
    private static String wideSeries(List<Double> values) {
        StringBuilder rows = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                rows.append(',');
            }
            rows.append("[\"2026-01-").append(String.format("%03d", index + 1)).append("\",")
                .append(values.get(index)).append(']');
        }
        return rows.append(']').toString();
    }

    @Test
    @DisplayName("平稳序列：没有偏离，不产生信号强度")
    void flatSeriesHasNoSignal() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(100.0);
        }
        DatasetSignal.Signal signal = DatasetSignal.of(snapshot("wide", "daily", wideSeries(values)), WINDOW, TODAY, FINANCE);

        assertNotNull(signal);
        assertEquals(0.0, signal.magnitude(), 1e-9, "完全没动的序列不该有信号");
        assertNull(signal.zscore(), "标准差为 0 时不该硬算 z-score");
        assertFalse(signal.extreme());
    }

    @Test
    @DisplayName("明显跳变：z-score 算得出来，且标记为新极值")
    void jumpIsDetected() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(100.0 + (index % 2) * 0.5);
        }
        values.add(130.0);
        DatasetSignal.Signal signal = DatasetSignal.of(snapshot("wide", "daily", wideSeries(values)), WINDOW, TODAY, FINANCE);

        assertNotNull(signal);
        assertNotNull(signal.zscore());
        assertTrue(signal.zscore() > 3, "130 相对 100 附近的小幅波动应当远超 3σ");
        assertTrue(signal.extreme(), "落在基线之上应判为新极值");
        assertEquals(1.0, signal.magnitude(), 1e-9, "超过封顶就按最强处理");
        assertTrue(signal.flags().stream().anyMatch(flag -> flag.contains("新高")), signal.flags().toString());
    }

    @Test
    @DisplayName("点太少时不硬算 z-score，只按这一期动没动判断")
    void tooFewPointsFallsBack() {
        JsonNode snapshot = snapshot("wide", "daily", wideSeries(List.of(10.0, 10.0, 12.0)));

        DatasetSignal.Signal signal = DatasetSignal.of(snapshot, WINDOW, TODAY, FINANCE);

        assertNotNull(signal);
        assertNull(signal.zscore(), "基线不足 5 点不应给 z-score");
        assertEquals("change", signal.signalType());
        assertFalse(signal.extreme(), "点太少时比出来的极值是噪声，不能算");
    }

    @Test
    @DisplayName("多实体序列：取偏离最大的那个实体")
    void longPicksStrongestEntity() {
        StringBuilder rows = new StringBuilder("[");
        for (int day = 0; day < 40; day++) {
            if (day > 0) {
                rows.append(',');
            }
            rows.append("[\"2026-01-").append(String.format("%03d", day + 1)).append("\",\"平稳国\",100]");
        }
        for (int day = 0; day < 40; day++) {
            rows.append(",[\"2026-01-").append(String.format("%03d", day + 1)).append("\",\"异常国\",")
                .append(day == 39 ? 150 : 100).append(']');
        }
        rows.append(']');

        DatasetSignal.Signal signal = DatasetSignal.of(snapshot("long", "annual", rows.toString()), WINDOW, TODAY, FINANCE);

        assertNotNull(signal);
        assertEquals("异常国", signal.entity(), "应当挑偏离最大的实体");
        assertTrue(signal.magnitude() > 0.5);
    }

    @Test
    @DisplayName("排行形态：按相对变化挑最大变动项")
    void rowsPickTopMover() {
        String rows = """
            [["甲",110,10],["乙",200,2],["丙",100,0]]
            """;

        DatasetSignal.Signal signal = DatasetSignal.of(snapshot("rows", "daily", rows), WINDOW, TODAY, FINANCE);

        assertNotNull(signal);
        assertEquals("甲", signal.entity());
        assertEquals("mover", signal.signalType());
        assertEquals(0.1, signal.changePct(), 1e-9, "10/100 = 10%");
    }

    @Test
    @DisplayName("排行没有变化列：不出机会，不拿数值大小当新闻")
    void rowsWithoutChangeProduceNothing() {
        assertNull(DatasetSignal.of(snapshot("rows", "daily", """
            [["甲",110],["乙",200]]
            """), WINDOW, TODAY, FINANCE));
    }

    @Test
    @DisplayName("地理形态没有时间轴：先不出机会")
    void geoProducesNothing() {
        assertNull(DatasetSignal.of(snapshot("geo", "annual", """
            [["中国","CN","CN",100],["美国","US","US",90]]
            """), WINDOW, TODAY, FINANCE));
    }

    @Test
    @DisplayName("评分：强信号过线，平稳信号不过线")
    void scoreSeparatesSignalFromNoise() {
        List<Double> quiet = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            quiet.add(100.0 + (index % 2) * 0.2);
        }
        DatasetSignal.Signal calm = DatasetSignal.of(snapshot("wide", "daily", wideSeries(quiet)),
            WINDOW, TODAY, FINANCE);

        List<Double> jump = new ArrayList<>(quiet);
        jump.set(jump.size() - 1, 150.0);
        DatasetSignal.Signal loud = DatasetSignal.of(snapshot("wide", "daily", wideSeries(jump)), WINDOW, TODAY, FINANCE);

        assertNotNull(calm);
        assertNotNull(loud);
        assertTrue(DatasetSignal.score(calm, 30) < DataOpportunityService.THRESHOLD,
            "平稳序列不该入榜，实际 " + DatasetSignal.score(calm, 30));
        assertTrue(DatasetSignal.score(loud, 30) >= DataOpportunityService.THRESHOLD,
            "跳变应当入榜，实际 " + DatasetSignal.score(loud, 30));
    }

    @Test
    @DisplayName("标题只用信号里的数字，并把窗口翻译成时间单位")
    void headlineUsesSignalNumbersOnly() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(100.0);
        }
        values.add(110.0);
        DatasetSignal.Signal signal = DatasetSignal.of(snapshot("wide", "daily", wideSeries(values)), WINDOW, TODAY, FINANCE);

        String headline = DatasetSignal.headline(signal, "伦敦金 XAU", "daily");

        assertTrue(headline.contains("伦敦金 XAU"), headline);
        assertTrue(headline.contains("日"), "cadence=daily 应翻译成「日」：" + headline);
        assertFalse(headline.contains("%%"), headline);
        assertTrue(headline.length() <= 200);
    }

    @Test
    @DisplayName("信号去重键包含窗口：换窗口就是另一条口径")
    void dedupPartCarriesWindow() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(100.0 + index);
        }
        DatasetSignal.Signal a = DatasetSignal.of(snapshot("wide", "daily", wideSeries(values)), 60, TODAY, FINANCE);
        DatasetSignal.Signal b = DatasetSignal.of(snapshot("wide", "daily", wideSeries(values)), 120, TODAY, FINANCE);

        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a.dedupPart(), b.dedupPart());
    }

    @Test
    @DisplayName("年频历史序列末点太旧：不当成「今天变了」")
    void staleAnnualSeriesIsDropped() {
        String rows = "["
            + "[\"2017-01\",100],[\"2018-01\",110],[\"2019-01\",121],"
            + "[\"2020-01\",133],[\"2021-01\",146],[\"2022-01\",160],[\"2023-01\",176]]";
        JsonNode snapshot = snapshot("wide", "annual", rows);

        assertNull(DatasetSignal.of(snapshot, WINDOW, java.time.YearMonth.of(2026, 1), FINANCE),
            "末点是 2023-01，按年频超过 18 个月容差应当判过期");
        assertNotNull(DatasetSignal.of(snapshot, WINDOW, java.time.YearMonth.of(2023, 6), FINANCE),
            "同一份数据在刚发布时是新鲜的");
    }

    @Test
    @DisplayName("幅度大到不合常理：按口径变化处理，不发")
    void implausibleChangeIsDropped() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(1.0);
        }
        values.add(12.0);
        JsonNode snapshot = snapshot("wide", "daily", wideSeries(values));

        assertNull(DatasetSignal.of(snapshot, WINDOW, TODAY, FINANCE),
            "+1100% 这种多半是单位/口径变了，不能当新闻发出去");
    }

    @Test
    @DisplayName("日期解析：只认年月，解析不了就不拦")
    void parseYearMonthIsLenient() {
        assertEquals(java.time.YearMonth.of(2021, 1), DatasetSignal.parseYearMonth("2021"));
        assertEquals(java.time.YearMonth.of(2021, 12), DatasetSignal.parseYearMonth("2021-12-31"));
        assertNull(DatasetSignal.parseYearMonth("n/a"));
        assertFalse(DatasetSignal.stale("n/a", "daily", TODAY), "解析不了不应判过期");
    }

    @Test
    @DisplayName("天气类不参与选题：预报天天在变")
    void weatherIsExcluded() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(20.0 + index);
        }
        JsonNode snapshot = snapshot("wide", "daily", wideSeries(values));

        assertNull(DatasetSignal.of(snapshot, WINDOW, TODAY, DatasetSignal.CategoryPolicy.of("weather")));
        assertNotNull(DatasetSignal.of(snapshot, WINDOW, TODAY, FINANCE), "换成价格类就照常出信号");
    }

    @Test
    @DisplayName("计数/排行类不报百分比，只报偏离")
    void countSeriesHasNoPercent() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(3.0 + (index % 2));
        }
        values.add(9.0);
        DatasetSignal.Signal signal = DatasetSignal.of(snapshot("wide", "daily", wideSeries(values)),
            WINDOW, TODAY, DatasetSignal.CategoryPolicy.of("news"));

        assertNotNull(signal);
        assertNull(signal.changePct(), "计数类的百分比在小编数上没有意义，不该出现");
        assertNotNull(signal.zscore(), "偏离仍然有效");
        assertFalse(DatasetSignal.headline(signal, "USGS", "daily").contains("%"),
            "标题里不能出现百分比");
    }

    @Test
    @DisplayName("同一幅度，宏观比股指更值得做：基准不同")
    void macroScalesSmallerMovesHigher() {
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            values.add(100.0 + (index % 2) * 0.05);
        }
        values.add(100.3);
        JsonNode snapshot = snapshot("wide", "daily", wideSeries(values));

        DatasetSignal.Signal macro = DatasetSignal.of(snapshot, WINDOW, TODAY,
            DatasetSignal.CategoryPolicy.of("macro"));
        DatasetSignal.Signal finance = DatasetSignal.of(snapshot, WINDOW, TODAY, FINANCE);

        assertNotNull(macro);
        assertNotNull(finance);
        assertEquals(0.02, macro.changeScale(), 1e-9);
        assertEquals(0.05, finance.changeScale(), 1e-9);
        assertTrue(DatasetSignal.score(macro, 20) > DatasetSignal.score(finance, 20),
            "宏观 0.3% 应该比股指 0.3% 得分高");
    }

    @Test
    @DisplayName("单期排行只对百分比有意义的类别开放")
    void rowsOnlyForPercentCategories() {
        String rows = """
            [["甲",110,10],["乙",200,2]]
            """;

        assertNull(DatasetSignal.of(snapshot("rows", "daily", rows), WINDOW, TODAY,
            DatasetSignal.CategoryPolicy.of("gaming")), "榜单类的相对幅度说明不了什么");
        assertNotNull(DatasetSignal.of(snapshot("rows", "daily", rows), WINDOW, TODAY, FINANCE));
    }
}
