package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronStrategyHypothesis;
import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.domain.vo.StrategyHypothesisVo;
import org.dromara.heron.mapper.HeronStrategyHypothesisMapper;
import org.dromara.heron.service.INewsMetricService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型策略假设：从归因分组里读出相关，再让模型提出「怎么验证」。
 *
 * <p>假设不是结论，也不是预测。四道过滤把它们挡在「结论」之外：
 * 分组必须真的存在（不得自造）、验证条件必须够硬（≥30 条、跨 ≥2 周）、
 * 指标必须是闭集里的一种、措辞不得出现任何预测。
 *
 * <p>生成是人工触发的，一次调用最多写入 5 条，只追加不改写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyHypothesisService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/strategy/hypothesis.md");
    private static final String SCHEMA_VERSION = "strategy-hypothesis/1";
    private static final String PROMPT_VERSION = "strategy-hypothesis-v1";
    /** 只看能从冻结输入直接读出来的四维，外加机会信号；别的维度与策略无关。 */
    private static final List<String> DIMENSIONS = List.of("style", "column", "hook", "opening", "signal");
    private static final Set<String> METRICS = Set.of("medianPlays", "medianCompletionRate", "outlierRatio");
    private static final int MAX_HYPOTHESES = 5;
    private static final int MAX_ROWS = 20;
    /** 少于 3 条的分组不进输入：这种量级连相关都看不出来。 */
    private static final int MIN_BUCKET_SAMPLES = 3;
    private static final int MIN_VERIFY_SAMPLES = 30;
    private static final int MIN_VERIFY_WEEKS = 2;
    /** 预测性措辞一律拒收：假设区不是预测器。 */
    private static final Pattern PREDICTIVE = Pattern.compile("爆款概率|预计|预估|将会|大概能|预测");

    private final INewsMetricService metrics;
    private final OpenAiModelClient model;
    private final HeronStrategyHypothesisMapper hypotheses;
    private final HeronAccessService access;

    /** 已落库的假设，新的在前。 */
    public List<StrategyHypothesisVo> list(int limit) {
        int size = limit <= 0 ? MAX_ROWS : Math.min(limit, MAX_ROWS);
        return hypotheses.selectPage(new Page<HeronStrategyHypothesis>(1, size, false),
                QueryBuilder.lambda(HeronStrategyHypothesis.class)
                    .orderByDesc(HeronStrategyHypothesis::getGeneratedAt)
                    .build())
            .getRecords().stream().map(StrategyHypothesisService::toVo).toList();
    }

    /**
     * 让模型基于当前归因分组提一批假设。
     *
     * <p>没有分组的样本量够看就什么都不调——让模型在空数据上编假设，等于给它一个编造的入口。
     */
    public List<StrategyHypothesisVo> generate() {
        Long actor = access.requireActor();
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        Map<String, String> basis = new LinkedHashMap<>();
        String input = input(allowed, basis);
        if (allowed.isEmpty()) {
            throw new HeronBusinessException(409, "strategy_hypothesis_no_data",
                "还没有可用于推导假设的分组样本（每组至少 " + MIN_BUCKET_SAMPLES + " 条）");
        }
        String reply = model.chatWithoutUsage(SYSTEM, input);
        List<Row> rows = parse(reply, allowed, basis);
        if (rows.isEmpty()) {
            throw new HeronBusinessException(409, "strategy_hypothesis_invalid",
                "模型没有给出可验证的假设，未写入任何记录");
        }
        String modelName = model.modelName();
        String source = (modelName == null || modelName.isBlank() ? "model=unconfigured" : "model=" + modelName)
            + " · " + PROMPT_VERSION;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<StrategyHypothesisVo> out = new ArrayList<>();
        for (Row row : rows) {
            HeronStrategyHypothesis entity = new HeronStrategyHypothesis();
            entity.setId(IdUtil.getSnowflakeNextId());
            entity.setSchemaVersion(SCHEMA_VERSION);
            entity.setClaim(row.claim());
            entity.setDimension(row.dimension());
            entity.setBucketValue(row.bucket());
            entity.setMetric(row.metric());
            entity.setMinSamples(row.minSamples());
            entity.setMinWeeks(row.minWeeks());
            entity.setFalsifyIf(row.falsifyIf());
            entity.setBasis(row.basis());
            entity.setSource(source);
            entity.setGeneratedBy(actor);
            entity.setGeneratedAt(now);
            if (hypotheses.insert(entity) != 1) {
                throw new HeronBusinessException(409, "strategy_hypothesis_write", "假设写入失败");
            }
            out.add(toVo(entity));
        }
        return out;
    }

    /** 把各维度的分组统计摊成给模型的输入，同时记下「允许出现哪些分组」。 */
    private String input(Map<String, List<String>> allowed, Map<String, String> basis) {
        StringBuilder out = new StringBuilder();
        for (String dimension : DIMENSIONS) {
            NewsPerformanceInsightVo insight;
            try {
                insight = metrics.performanceInsight(dimension);
            } catch (RuntimeException e) {
                // 某一维度算不出来不该拖垮其他维度；它本来就只是候选输入
                log.warn("归因维度 {} 算不出来，跳过：{}", dimension, e.getMessage());
                continue;
            }
            List<NewsPerformanceInsightVo.Bucket> buckets =
                insight.getBuckets() == null ? List.of() : insight.getBuckets();
            List<String> names = new ArrayList<>();
            for (NewsPerformanceInsightVo.Bucket bucket : buckets) {
                if (bucket.getSamples() < MIN_BUCKET_SAMPLES || "未记录".equals(bucket.getBucket())) continue;
                String key = dimension + " · " + bucket.getBucket();
                String line = key + "｜n=" + bucket.getSamples() + "｜" + bucket.getTier()
                    + "｜播放中位数 " + number(bucket.getMedianPlays())
                    + "｜完播中位数 " + percent(bucket.getMedianCompletionRate())
                    + "｜离群比 " + number(bucket.getOutlierRatio())
                    + "｜" + (bucket.isStable() ? "去掉最高一条方向不变" : "去掉最高一条方向会变");
                out.append("- ").append(line).append('\n');
                names.add(bucket.getBucket());
                basis.put(dimension + "|" + bucket.getBucket(), line);
            }
            if (!names.isEmpty()) {
                allowed.put(dimension, names);
            }
        }
        return out.toString();
    }

    /** 解析并过滤模型输出；过滤掉的条目不报错，只是不进库。 */
    private static List<Row> parse(String reply, Map<String, List<String>> allowed, Map<String, String> basis) {
        JsonNode root;
        try {
            root = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "strategy_hypothesis_failed", "模型输出不是 JSON");
        }
        if (root == null || !root.isObject() || !root.path("hypotheses").isArray()) {
            throw new HeronBusinessException(503, "strategy_hypothesis_failed", "模型输出缺少 hypotheses");
        }
        List<Row> rows = new ArrayList<>();
        for (JsonNode item : root.path("hypotheses")) {
            if (rows.size() >= MAX_HYPOTHESES) break;
            String dimension = item.path("dimension").asString("");
            String bucket = item.path("bucketValue").asString("");
            String claim = item.path("claim").asString("").strip();
            String metric = item.path("metric").asString("");
            String falsifyIf = item.path("falsifyIf").asString("").strip();
            int minSamples = item.path("minSamples").asInt(0);
            int minWeeks = item.path("minWeeks").asInt(0);
            // 四道过滤：编造的分组、说不清怎么验证、指标不在闭集、带预测措辞
            if (!allowed.getOrDefault(dimension, List.of()).contains(bucket)) continue;
            if (claim.isEmpty() || claim.length() > 500 || falsifyIf.isEmpty() || falsifyIf.length() > 500) continue;
            if (!METRICS.contains(metric) || minSamples < MIN_VERIFY_SAMPLES || minWeeks < MIN_VERIFY_WEEKS) continue;
            if (PREDICTIVE.matcher(claim).find() || PREDICTIVE.matcher(falsifyIf).find()) continue;
            rows.add(new Row(claim, dimension, bucket, metric, minSamples, minWeeks, falsifyIf,
                basis.getOrDefault(dimension + "|" + bucket, dimension + " · " + bucket)));
        }
        return rows;
    }

    private static StrategyHypothesisVo toVo(HeronStrategyHypothesis entity) {
        return new StrategyHypothesisVo(String.valueOf(entity.getId()), entity.getClaim(),
            entity.getDimension(), entity.getBucketValue(), entity.getMetric(),
            entity.getMinSamples() == null ? 0 : entity.getMinSamples(),
            entity.getMinWeeks() == null ? 0 : entity.getMinWeeks(),
            entity.getFalsifyIf(), entity.getBasis(), entity.getSource(),
            entity.getGeneratedAt() == null ? "" : entity.getGeneratedAt().toString());
    }

    private static String number(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.ROOT, "%.0f", value);
    }

    private static String percent(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.ROOT, "%.0f%%", value * 100);
    }

    private static String resource(String path) {
        try (InputStream stream = StrategyHypothesisService.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }

    /** 一条通过校验的假设，落库前的形态。 */
    private record Row(String claim, String dimension, String bucket, String metric, int minSamples,
                       int minWeeks, String falsifyIf, String basis) {
    }
}
