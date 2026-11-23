package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsMetric;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.bo.NewsMetricBo;
import org.dromara.heron.domain.vo.NewsMetricVo;
import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.domain.vo.NewsSuggestionVo;
import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.INewsMetricService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 发布表现：只做记录与读取，不做推算；没有数据时如实返回空表。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsMetricServiceImpl implements INewsMetricService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_SUGGESTIONS = 20;
    /** 创作建议只取模型给的主观条目；客观条目的结论是算出来的，不该出现在这一区。 */
    private static final Set<String> MODEL_CLASSES = Set.of("subjective", "hypothesis");
    /** pass 没有可改的东西，unknown 没有内容；只有这两档才是真的提了建议。 */
    private static final Set<String> ACTIONABLE_VERDICTS = Set.of("minor", "fail");

    private final HeronNewsMetricMapper metrics;
    private final HeronVideoVersionMapper versions;
    private final HeronProductionProperties properties;

    @Override
    public NewsMetricVo save(NewsMetricBo bo) {
        if (bo == null || bo.versionId() == null || bo.platform() == null || bo.platform().isBlank()) {
            throw new HeronBusinessException(400, "metric_invalid", "版本与平台不能为空");
        }
        HeronVideoVersion version = versions.selectById(bo.versionId());
        if (version == null) {
            throw new HeronBusinessException(404, "metric_version_missing", "版本不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        HeronNewsMetric metric = new HeronNewsMetric();
        metric.setMetricId(IdUtil.getSnowflakeNextId());
        metric.setVersionId(bo.versionId());
        metric.setPlatform(bo.platform().strip());
        metric.setPlays(bo.plays());
        metric.setCompletionRate(bo.completionRate());
        metric.setLikes(bo.likes());
        metric.setComments(bo.comments());
        metric.setShares(bo.shares());
        metric.setFetchedAt(bo.fetchedAt() == null ? now : bo.fetchedAt());
        metric.setCreateTime(now);
        if (metrics.insert(metric) != 1) {
            throw new HeronBusinessException(409, "metric_write", "表现记录保存失败");
        }
        NewsMetricVo vo = new NewsMetricVo();
        vo.setMetricId(metric.getMetricId());
        vo.setVersionId(metric.getVersionId());
        vo.setPlatform(metric.getPlatform());
        vo.setPlays(metric.getPlays());
        vo.setCompletionRate(metric.getCompletionRate());
        vo.setLikes(metric.getLikes());
        vo.setComments(metric.getComments());
        vo.setShares(metric.getShares());
        vo.setFetchedAt(metric.getFetchedAt());
        return vo;
    }

    @Override
    public List<NewsMetricVo> byVersion(long versionId) {
        return metrics.selectList(QueryBuilder.lambda(HeronNewsMetric.class)
                .eq(HeronNewsMetric::getVersionId, versionId)
                .orderByDesc(HeronNewsMetric::getFetchedAt).build())
            .stream().map(this::toVo).toList();
    }

    @Override
    public List<HeronNewsMetricMapper.Performance> recentPerformance(int limit) {
        return metrics.recentPerformance(Math.max(1, limit));
    }

    /**
     * 归因结论：按维度分桶，用中位数和样本等级说话。
     *
     * <p>和旧版只给均值的汇总相比，结论有了分量：
     * 样本够不够、稳不稳，决定这一条能当结论还是只能当观察。
     */
    @Override
    public NewsPerformanceInsightVo performanceInsight(String dimension) {
        String key = dimension == null ? "" : dimension.strip().toLowerCase();
        if (!MetricInsightAnalyzer.DIMENSIONS.contains(key)) {
            throw new HeronBusinessException(400, "metric_dimension_invalid",
                "只支持按 " + String.join(" / ", MetricInsightAnalyzer.DIMENSIONS) + " 汇总，收到：" + dimension);
        }
        List<MetricInsightAnalyzer.Sample> samples = metrics.performanceSamples().stream()
            .map(this::toSample)
            .toList();
        return MetricInsightAnalyzer.analyze(key, samples);
    }

    /**
     * 创作建议：L3 报告里模型给的主观条目。
     *
     * <p>只取 subjective / hypothesis 且不是 pass 的条目——客观条目的结论是算出来的，
     * 放进建议区会让「算出来的」和「模型说的」混成一类。这些条目不是结论也不是预测：
     * 采纳与否由人决定，采纳后要显式记一条策略覆盖，而不是让页面替人做决定。
     */
    @Override
    public List<NewsSuggestionVo> suggestions(int limit) {
        int size = limit <= 0 ? MAX_SUGGESTIONS : Math.min(limit, MAX_SUGGESTIONS);
        List<HeronVideoVersion> rows = versions.selectPage(new Page<HeronVideoVersion>(1, size, false),
            QueryBuilder.lambda(HeronVideoVersion.class)
                .isNotNull(HeronVideoVersion::getQualityReport)
                .orderByDesc(HeronVideoVersion::getUpdateTime)
                .build()).getRecords();
        List<NewsSuggestionVo> out = new ArrayList<>();
        for (HeronVideoVersion row : rows) {
            JsonNode report = readTree(row.getQualityReport(), row.getVersionId());
            String promptVersion = report.path("promptVersion").asString("");
            String modelId = report.path("modelId").asString("");
            String source = "L3 质量审稿"
                + (promptVersion.isBlank() ? "" : " · " + promptVersion)
                + (modelId.isBlank() ? "" : " · " + modelId);
            for (JsonNode item : report.path("criteria")) {
                String criterionClass = item.path("class").asString("");
                String verdict = item.path("verdict").asString("");
                String reason = item.path("reason").asString("");
                if (!MODEL_CLASSES.contains(criterionClass) || !ACTIONABLE_VERDICTS.contains(verdict)
                    || reason.isBlank()) {
                    continue;
                }
                String criterionId = item.path("id").asString("");
                out.add(new NewsSuggestionVo(row.getVersionId() + ":" + criterionId, row.getVersionId(),
                    criterionId, reason, source, "hypothesis".equals(criterionClass), criterionClass,
                    item.path("confidence").asString("")));
                if (out.size() >= size) {
                    return out;
                }
            }
        }
        return out;
    }

    /** 一行采样 + 这版视频身上能直接读出来的创作属性。读不到的属性留空，交给分桶归到「未记录」。 */
    MetricInsightAnalyzer.Sample toSample(HeronNewsMetricMapper.PerformanceSample row) {
        JsonNode snapshot = readTree(row.inputSnapshot(), row.versionId());
        JsonNode script = readTree(row.script(), row.versionId());
        JsonNode profile = readTree(row.contentProfile(), row.versionId());
        return new MetricInsightAnalyzer.Sample(
            row.versionId(),
            row.plays(),
            row.completionRate(),
            row.likes(),
            row.publishAt(),
            row.fetchedAt(),
            row.durationMs(),
            text(snapshot, "selection", "templateId"),
            text(snapshot, "editorial", "line"),
            text(snapshot, "editorial", "storyAngle"),
            text(snapshot, "kind"),
            text(script, "chart"),
            text(snapshot, "selection", "dataSourceKey"),
            row.sourceKind(),
            row.candidateId(),
            signalType(snapshot),
            styleKey(text(snapshot, "selection", "templateId")),
            text(snapshot, "assets", "voiceKey"),
            text(profile, "hookType"),
            text(profile, "opening", "shape"));
    }

    /**
     * 模板的视觉风格键。
     *
     * <p>风格写在模板自己的 template.json 里，是确定性的映射，不需要另存一份。
     * 读不到就归「未记录」，不猜——模板可能被换掉，也可能根本不是本地这套。
     */
    private String styleKey(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        Path root = properties.getNewsTemplate();
        if (root == null) {
            return null;
        }
        Path meta = root.toAbsolutePath().normalize().resolve(templateId).resolve("template.json");
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(meta, StandardCharsets.UTF_8));
            String key = node.path("style").path("key").asString("");
            return key.isBlank() ? null : key;
        } catch (IOException | RuntimeException e) {
            log.warn("模板 {} 的风格读不出来，归因按未记录处理: {}", templateId, e.getMessage());
            return null;
        }
    }

    /**
     * 机会信号类型。冻结输入里的信号是编辑决策的输入之一，
     * 拿它归因才能把「哪类数据机会更容易被看完」和播放表现挂上。
     *
     * <p>信号挂在 editorial 节点下（{@code editorial.opportunitySignals}）：
     * 它是「选题当时的判断」，属于编辑决策的一部分，不在 selection 节点里。
     */
    private static String signalType(JsonNode snapshot) {
        return firstSignalType(at(snapshot, "editorial.opportunitySignals"));
    }

    private static String firstSignalType(JsonNode signals) {
        if (signals == null || signals.isNull()) return null;
        if (signals.isArray()) {
            return signals.size() == 0 ? null : text(signals.get(0), "signal_type");
        }
        if (signals.isObject()) {
            String direct = text(signals, "signal_type");
            if (direct != null) return direct;
            for (var entry : signals.properties()) {
                String type = text(entry.getValue(), "signal_type");
                if (type != null) return type;
            }
        }
        return null;
    }

    private static JsonNode at(JsonNode node, String path) {
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject()) return null;
            current = current.get(segment);
        }
        return current;
    }

    private static String text(JsonNode node, String... path) {
        JsonNode current = node;
        for (String segment : path) {
            if (current == null || !current.isObject()) return null;
            current = current.get(segment);
        }
        if (current == null || current.isNull() || !current.isTextual()) return null;
        return current.asString();
    }

    /** 快照解析不了只丢归因，不丢这条播放数据；解析不了本身要留痕。 */
    private static JsonNode readTree(String json, Long versionId) {
        if (json == null || json.isBlank()) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(json);
            return node == null ? JSON.createObjectNode() : node;
        } catch (RuntimeException e) {
            log.warn("表现归因读不懂版本 {} 的归因字段，属性按未记录处理: {}", versionId, e.getMessage());
            return JSON.createObjectNode();
        }
    }

    private NewsMetricVo toVo(HeronNewsMetric metric) {
        NewsMetricVo vo = new NewsMetricVo();
        vo.setMetricId(metric.getMetricId());
        vo.setVersionId(metric.getVersionId());
        vo.setPlatform(metric.getPlatform());
        vo.setPlays(metric.getPlays());
        vo.setCompletionRate(metric.getCompletionRate());
        vo.setLikes(metric.getLikes());
        vo.setComments(metric.getComments());
        vo.setShares(metric.getShares());
        vo.setFetchedAt(metric.getFetchedAt());
        return vo;
    }
}
