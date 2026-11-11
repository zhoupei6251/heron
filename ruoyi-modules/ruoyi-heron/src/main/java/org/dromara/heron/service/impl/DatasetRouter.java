package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronDatasetUsageMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 热点题材 → 注册表闭集选源。模型只做选择题，不发现新源。
 * 7 天内用过的源不参与选择；选不中降级为数据速览，全部用尽则空槽告警。
 */
@Service
@RequiredArgsConstructor
public class DatasetRouter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/heron/dataset-routing.md");
    private static final int DEDUP_DAYS = 7;

    private final OpenAiModelClient model;
    private final HeronDataSourceMapper dataSourceMapper;
    private final HeronDatasetUsageMapper usageMapper;

    /** digest=true 表示没有热点匹配，按最新更新的源出「数据速览」。 */
    public record Route(String sourceKey, boolean digest) { }

    public Route route(ProductionRunVo run, JsonNode input) {
        List<HeronDataSource> enabled = dataSourceMapper.enabledSources().stream()
            .filter(source -> "ok".equals(source.getProbeStatus())).toList();
        if (enabled.isEmpty()) throw slotEmpty("没有探测为 ok 的启用源");
        Set<String> recent = usageMapper.recentSourceKeys(LocalDateTime.now().minusDays(DEDUP_DAYS));
        List<HeronDataSource> eligible = enabled.stream()
            .filter(source -> !recent.contains(source.getSourceKey())).toList();
        if (eligible.isEmpty()) throw slotEmpty("7 天内可用的数据源已用尽");
        String reply = model.chat(run, "dataset-route", SYSTEM, prompt(input, eligible));
        return plan(reply, eligible,
            enabled.stream().map(HeronDataSource::getSourceKey).collect(Collectors.toSet()));
    }

    /** 纯决策：热点命中出热点片；none 或命中近 7 天已用源时降级速览。 */
    static Route plan(String reply, List<HeronDataSource> eligible, Set<String> enabledKeys) {
        String key;
        try {
            JsonNode node = reply == null ? null : JSON.readTree(ModelJson.unwrap(reply));
            key = node == null ? "" : node.path("source_key").asString("");
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "dataset_route_invalid", "选源输出不是 JSON");
        }
        if (key.isBlank()) {
            throw new HeronBusinessException(409, "dataset_route_invalid", "选源输出缺少 source_key");
        }
        if ("none".equals(key)) return digest(eligible);
        if (!enabledKeys.contains(key)) {
            throw new HeronBusinessException(409, "dataset_route_invalid", "模型选了未启用的源：" + key);
        }
        if (eligible.stream().noneMatch(source -> source.getSourceKey().equals(key))) {
            return digest(eligible);
        }
        return new Route(key, false);
    }

    /** 速览按栏目权重轮换（20 期），栏内再按更新频率与最近更新选。 */
    static Route digest(List<HeronDataSource> eligible) {
        int start = Math.floorMod(LocalDate.now().getDayOfYear(), CYCLE.size());
        for (int offset = 0; offset < CYCLE.size(); offset++) {
            Column column = CYCLE.get((start + offset) % CYCLE.size());
            HeronDataSource pick = bestIn(eligible, column);
            if (pick != null) return new Route(pick.getSourceKey(), true);
        }
        HeronDataSource any = bestIn(eligible, null);
        if (any == null) throw slotEmpty("没有可用的数据源");
        return new Route(any.getSourceKey(), true);
    }

    private static HeronDataSource bestIn(List<HeronDataSource> eligible, Column column) {
        return eligible.stream()
            .filter(source -> column == null || Column.forSource(source.getSourceKey()) == column)
            .max(Comparator.<HeronDataSource>comparingInt(source -> cadenceRank(source.getCadence()))
                .thenComparing(HeronDataSource::getLastUpdated, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(HeronDataSource::getSourceKey))
            .orElse(null);
    }

    /** 20 期一轮：科技 6、市场 4、商业 4、趋势 3、图鉴 3。 */
    static List<Column> cycle() {
        List<Column> cycle = new ArrayList<>();
        for (int index = 0; index < 6; index++) cycle.add(Column.TECH);
        for (int index = 0; index < 4; index++) cycle.add(Column.MARKET);
        for (int index = 0; index < 4; index++) cycle.add(Column.BUSINESS);
        for (int index = 0; index < 3; index++) cycle.add(Column.TREND);
        for (int index = 0; index < 3; index++) cycle.add(Column.ATLAS);
        return List.copyOf(cycle);
    }

    private static final List<Column> CYCLE = cycle();

    static int cadenceRank(String cadence) {
        return switch (cadence == null ? "" : cadence) {
            case "realtime", "daily" -> 3;
            case "monthly" -> 2;
            case "annual" -> 1;
            default -> 0;
        };
    }

    private static HeronBusinessException slotEmpty(String reason) {
        return new HeronBusinessException(409, "dataset_slot_empty", reason);
    }

    private static String prompt(JsonNode input, List<HeronDataSource> eligible) {
        StringBuilder text = new StringBuilder()
            .append("标题：").append(input.path("title").asString("")).append('\n')
            .append("草稿：").append(input.path("draft").asString("")).append('\n')
            .append("可选源（近 7 天未用过）：\n");
        for (HeronDataSource source : eligible) {
            Column column = Column.forSource(source.getSourceKey());
            text.append("- ").append(source.getSourceKey())
                .append(" | ").append(source.getName())
                .append(" | 栏目=").append(column.displayName()).append("（权重 ").append(column.weight()).append("）")
                .append(" | cadence=").append(source.getCadence())
                .append(" | shapes=").append(source.getShapes())
                .append('\n');
        }
        return text.append("按契约输出 JSON。热点同时匹配多个源时优先权重高的栏目，但不能为了权重牺牲题材相关性。")
            .toString();
    }

    private static String resource(String path) {
        try (InputStream stream = DatasetRouter.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
