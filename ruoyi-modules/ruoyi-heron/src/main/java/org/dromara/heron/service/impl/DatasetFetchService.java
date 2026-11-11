package org.dromara.heron.service.impl;

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
@Service
public class DatasetFetchService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    public Result fetch(ProductionRunVo run, String sourceKey, int limit) {
        HeronDataSource source = dataSourceMapper.selectByKey(sourceKey);
        requireReady(source, sourceKey);
        DatasetAdapter adapter = adapterFor(sourceKey);
        String json = adapter.fetch(limit);
        JsonNode snapshot = JSON.readTree(json);
        String actual = snapshot.path("shape").asString("");
        if (!adapter.shape().equals(actual)) {
            throw new HeronBusinessException(409, "dataset_shape_mismatch",
                "适配器声明 " + adapter.shape() + "，实际产出 " + actual + "：" + sourceKey);
        }
        List<String> violations = DatasetContract.violations(snapshot,
            Set.copyOf(dataSourceMapper.enabledKeys()));
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_contract", "快照退回：" + String.join("；", violations));
        }
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
        if (source == null) {
            throw new HeronBusinessException(409, "dataset_source_missing", "注册表里没有该源：" + sourceKey);
        }
        if (!"0".equals(source.getEnabled())) {
            throw new HeronBusinessException(409, "dataset_source_disabled", "该源未启用：" + sourceKey);
        }
        if (!"ok".equals(source.getProbeStatus())) {
            throw new HeronBusinessException(503, "dataset_source_blocked", "该源探测状态不是 ok：" + sourceKey);
        }
    }
}
