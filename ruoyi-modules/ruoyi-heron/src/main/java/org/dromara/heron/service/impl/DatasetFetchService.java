package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据看板第一步：注册表 → 抓取 → dataset/1 闸 → 快照定稿。
 * 只接已登记且探测为 ok 的源；没有适配器的源直接拒绝，不在运行期现编。
 */
@Slf4j
@Service
public class DatasetFetchService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 连续失败这么多次才把源判成 blocked；单次抖动只记计数。 */
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final String PROBE_OK = "ok";
    static final String PROBE_BLOCKED = "blocked";
    /** probe_note 列宽 500，截断后再写，避免写库报错。 */
    private static final int PROBE_NOTE_MAX = 500;

    private final HeronDataSourceMapper dataSourceMapper;
    private final ProductionArtifactService artifacts;
    private final Map<String, DatasetAdapter> adapters;

    public DatasetFetchService(HeronDataSourceMapper dataSourceMapper, ProductionArtifactService artifacts) {
        this.dataSourceMapper = dataSourceMapper;
        this.artifacts = artifacts;
        List<DatasetAdapter> all = new java.util.ArrayList<>();
        all.add(new SinaFuturesDatasetAdapter(SinaFuturesDatasetAdapter.GOLD));
        all.add(new SinaFuturesDatasetAdapter(SinaFuturesDatasetAdapter.OIL));
        EastmoneyIndexAdapter.ALL.forEach(spec -> all.add(new EastmoneyIndexAdapter(spec)));
        FrankfurterAdapter.ALL.forEach(spec -> all.add(new FrankfurterAdapter(spec)));
        MetNoAdapter.ALL.forEach(spec -> all.add(new MetNoAdapter(spec)));
        EastmoneyMacroAdapter.ALL.forEach(spec -> all.add(new EastmoneyMacroAdapter(spec)));
        all.add(new EastmoneyMarketCapAdapter());
        UsgsEarthquakeAdapter.ALL.forEach(spec -> all.add(new UsgsEarthquakeAdapter(spec)));
        WikimediaTopAdapter.ALL.forEach(spec -> all.add(new WikimediaTopAdapter(spec)));
        all.add(new HackerNewsAdapter());
        GithubTrendingAdapter.ALL.forEach(spec -> all.add(new GithubTrendingAdapter(spec)));
        HuggingFaceAdapter.ALL.forEach(spec -> all.add(new HuggingFaceAdapter(spec)));
        JikanTopAdapter.ALL.forEach(spec -> all.add(new JikanTopAdapter(spec)));
        CheapSharkAdapter.ALL.forEach(spec -> all.add(new CheapSharkAdapter(spec)));
        WorldBankRankingAdapter.ALL.forEach(spec -> all.add(new WorldBankRankingAdapter(spec)));
        StackExchangeAdapter.ALL.forEach(spec -> all.add(new StackExchangeAdapter(spec)));
        DevToAdapter.ALL.forEach(spec -> all.add(new DevToAdapter(spec)));
        OwidSeriesAdapter.ALL.forEach(spec -> all.add(new OwidSeriesAdapter(spec)));
        OwidRaceAdapter.ALL.forEach(spec -> all.add(new OwidRaceAdapter(spec)));
        all.add(new GapminderFertilityAdapter());
        all.add(new VegaMoviesAdapter());
        this.adapters = all.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(DatasetAdapter::sourceKey, adapter -> adapter));
    }

    public record Result(String json, String dataSha256, ProductionArtifactVo artifact) { }

    /** 取数 + 校验的唯一实现：生产与分析共用，避免两份口径。 */
    private record Fetched(String json, JsonNode node) { }

    /**
     * @param requireHealthy 生产要求源当前是 ok；分析不要求——分析本身就是探测，
     *                       被 blocked 的源只能靠分析成功一次才能恢复
     */
    private Fetched fetched(String sourceKey, int limit, boolean requireHealthy) {
        HeronDataSource source = dataSourceMapper.selectByKey(sourceKey);
        requireReady(source, sourceKey, requireHealthy);
        DatasetAdapter adapter = adapterFor(sourceKey);
        String json;
        try {
            json = adapter.fetch(limit);
        } catch (RuntimeException e) {
            health(() -> markFailed(source, e.getMessage()));
            throw e;
        }
        JsonNode snapshot = JSON.readTree(json);
        String actual = snapshot.path("shape").asString("");
        if (!adapter.shape().equals(actual)) {
            HeronBusinessException error = new HeronBusinessException(409, "dataset_shape_mismatch",
                "适配器声明 " + adapter.shape() + "，实际产出 " + actual + "：" + sourceKey);
            health(() -> markFailed(source, error.getMessage()));
            throw error;
        }
        List<String> violations = DatasetContract.violations(snapshot,
            Set.copyOf(dataSourceMapper.enabledKeys()));
        if (!violations.isEmpty()) {
            HeronBusinessException error = new HeronBusinessException(409, "dataset_contract",
                "快照退回：" + String.join("；", violations));
            health(() -> markFailed(source, error.getMessage()));
            throw error;
        }
        // 走到这里说明这个源真的产出了一份合格快照——这就是一次成功的探测
        health(() -> dataSourceMapper.markHealthy(sourceKey, LocalDateTime.now()));
        return new Fetched(json, snapshot);
    }

    /** 连续失败到这个次数才判 blocked；单次网络抖动只记计数，不直接把源停掉。 */
    static String statusAfterFailure(int failures) {
        return failures >= MAX_CONSECUTIVE_FAILURES ? PROBE_BLOCKED : PROBE_OK;
    }

    private void markFailed(HeronDataSource source, String message) {
        int failures = (source.getFailureCount() == null ? 0 : source.getFailureCount()) + 1;
        String note = message == null ? "" : message;
        if (note.length() > PROBE_NOTE_MAX) {
            note = note.substring(0, PROBE_NOTE_MAX);
        }
        dataSourceMapper.markFailed(source.getSourceKey(), failures, statusAfterFailure(failures),
            note, LocalDateTime.now());
    }

    /** 健康记账失败不能让取数结果变味：只记日志，不覆盖真正的错误。 */
    private void health(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("更新源健康状态失败（不影响取数结果）: {}", e.getMessage());
        }
    }

    /**
     * 只取数不落产物：分析层要的是序列本身，不需要 work 目录、artifact 与新鲜度记账。
     * 校验和生产走同一条 {@link #fetched}，分析看到的快照与生产看到的完全同口径。
     *
     * <p>不要求 probe ok：分析是唯一的探测入口，卡住 blocked 的源就永远恢复不了。
     */
    public JsonNode snapshot(String sourceKey, int points) {
        return fetched(sourceKey, points, false).node();
    }

    public Result fetch(ProductionRunVo run, String sourceKey, int limit) {
        Fetched fetched = fetched(sourceKey, limit, true);
        String json = fetched.json();
        JsonNode snapshot = fetched.node();
        Path file = artifacts.prepareWorkDirectory(run).resolve("dataset.json");
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 dataset.json 失败", e);
        }
        ProductionArtifactVo artifact = artifacts.finalizeFile(run, "dataset", file, "application/json");
        // 新鲜度是速览降级的排序依据：抓取通过闸后立刻更新，失败不更新。
        int updated = dataSourceMapper.lambda()
            .set(HeronDataSource::getLastUpdated, LocalDateTime.now())
            .set(HeronDataSource::getUpdateTime, LocalDateTime.now())
            .eq(HeronDataSource::getSourceKey, sourceKey)
            .updateCount();
        if (updated != 1) {
            throw new HeronBusinessException(503, "dataset_source_missing", "更新源新鲜度失败：" + sourceKey);
        }
        return new Result(json, snapshot.path("data_sha256").asString(), artifact);
    }

    DatasetAdapter adapterFor(String sourceKey) {
        DatasetAdapter adapter = adapters.get(sourceKey);
        if (adapter == null) {
            throw new HeronBusinessException(503, "dataset_adapter_missing", "该源还没有适配器：" + sourceKey);
        }
        return adapter;
    }

    static void requireReady(HeronDataSource source, String sourceKey) {
        requireReady(source, sourceKey, false);
    }

    /**
     * @param ignoreProbe 分析用：不拦 probe 状态，让 blocked 的源也走一次取数——
     *                    成功就恢复 ok，失败就继续累加。生产不允许绕过。
     */
    static void requireReady(HeronDataSource source, String sourceKey, boolean ignoreProbe) {
        if (source == null) {
            throw new HeronBusinessException(409, "dataset_source_missing", "注册表里没有该源：" + sourceKey);
        }
        if (!"0".equals(source.getEnabled())) {
            throw new HeronBusinessException(409, "dataset_source_disabled", "该源未启用：" + sourceKey);
        }
        if (!ignoreProbe && !PROBE_OK.equals(source.getProbeStatus())) {
            throw new HeronBusinessException(503, "dataset_source_blocked", "该源探测状态不是 ok：" + sourceKey);
        }
    }
}
