package org.dromara.heron.service.impl.lane.databoard;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据看板的取数口径闸。
 *
 * <p>为什么要有这道闸：选题分析按 {@code HeronNewsProperties.dataAnalysisWindowPoints} 取数（默认 120 点），
 * 生产链路过去固定取 30 点。两条口径归约出的 {@code data_sha256} 必然不同，
 * 「这片子用的数据是不是选题当时看到的那份」在系统里没有任何一处能回答——
 * 两边的哈希从来没有被放到一起比过。
 *
 * <p>口径随冻结输入落盘（{@code selection.datasetWindow}），生产按同一条口径取数，
 * 取回后逐项对齐：口径不一致直接拒；口径一致但上游数据变了，留痕不静默。
 */
public final class DatasetWindowContract {

    /** 冻结输入没给口径时的兜底点数，与改动前的生产取值一致。 */
    public static final int DEFAULT_POINTS = 30;

    /**
     * @param sourceKey 取数源
     * @param points 取数点数：时间序列是点数，排行是行数
     * @param expectedDataSha256 选题当时的快照哈希；为空表示当时没记下来，只能核口径不能核批次
     * @param shape 形态
     * @param cadence 节奏
     * @param metricKey 指标 key
     */
    public record Window(String sourceKey, int points, String expectedDataSha256,
                         String shape, String cadence, String metricKey) { }

    private DatasetWindowContract() { }

    /** 冻结输入里的口径；没有这一节点（老快照、非机会驱动）返回 null。 */
    public static Window windowOf(JsonNode input) {
        if (input == null) {
            return null;
        }
        JsonNode node = input.path("selection").path("datasetWindow");
        if (!node.isObject()) {
            return null;
        }
        return new Window(
            node.path("sourceKey").asString("").strip(),
            node.path("points").asInt(0),
            node.path("expectedDataSha256").asString("").strip(),
            node.path("shape").asString("").strip(),
            node.path("cadence").asString("").strip(),
            node.path("metricKey").asString("").strip());
    }

    /** 生产取数用多少点：冻结口径优先；没有冻结口径才用旧兜底。 */
    public static int pointsOf(JsonNode input) {
        Window window = windowOf(input);
        if (window == null) {
            return input == null ? DEFAULT_POINTS : input.path("limit").asInt(DEFAULT_POINTS);
        }
        return window.points();
    }

    /** 口径自身是否可用：源与点数缺一不可，不可用就没必要去取数。 */
    public static List<String> windowViolations(Window window) {
        List<String> items = new ArrayList<>();
        if (window == null) {
            return items;
        }
        if (window.sourceKey().isBlank()) {
            items.add("冻结口径没有数据源");
        }
        if (window.points() <= 0) {
            items.add("冻结口径的取数点数不是正数：" + window.points());
        }
        return items;
    }

    /** 取回的快照与冻结口径逐项对齐；空列表表示口径一致。 */
    public static List<String> violations(Window window, JsonNode dataset) {
        List<String> items = new ArrayList<>(windowViolations(window));
        if (window == null || dataset == null) {
            return items;
        }
        String sourceKey = dataset.path("source_key").asString("");
        if (!sourceKey.equals(window.sourceKey())) {
            items.add("数据源不一致：冻结 " + window.sourceKey() + "，实际 " + sourceKey);
        }
        String shape = dataset.path("shape").asString("");
        if (!window.shape().isBlank() && !shape.equals(window.shape())) {
            items.add("形态不一致：冻结 " + window.shape() + "，实际 " + shape);
        }
        String metricKey = dataset.path("metric").path("key").asString("");
        if (!window.metricKey().isBlank() && !metricKey.equals(window.metricKey())) {
            items.add("指标不一致：冻结 " + window.metricKey() + "，实际 " + metricKey);
        }
        String cadence = dataset.path("cadence").asString("");
        if (!window.cadence().isBlank() && !cadence.equals(window.cadence())) {
            items.add("节奏不一致：冻结 " + window.cadence() + "，实际 " + cadence);
        }
        return items;
    }

    /** 批次是否一致：本次取回的快照哈希与选题当时冻结的期望哈希相同。 */
    public static boolean sameBatch(Window window, JsonNode dataset) {
        if (window == null || dataset == null) {
            return false;
        }
        String expected = window.expectedDataSha256();
        return !expected.isBlank() && expected.equals(dataset.path("data_sha256").asString(""));
    }
}
