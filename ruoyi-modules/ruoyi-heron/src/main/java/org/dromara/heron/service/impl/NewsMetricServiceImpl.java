package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsMetric;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.bo.NewsMetricBo;
import org.dromara.heron.domain.vo.NewsMetricVo;
import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.INewsMetricService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 发布表现：只做记录与读取，不做推算；没有数据时如实返回空表。 */
@Service
@RequiredArgsConstructor
public class NewsMetricServiceImpl implements INewsMetricService {
    /** 白名单：这三个值会进 XML 的 choose 分支，不能放任意输入过去。 */
    private static final java.util.Set<String> GROUP_BY_KEYS = java.util.Set.of("template", "line", "angle");

    private final HeronNewsMetricMapper metrics;
    private final HeronVideoVersionMapper versions;

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

    /** 只认三个已知维度：把未知值拼进 SQL 会被 groupBy 直接带进 wapper 的字符串位置。 */
    @Override
    public List<HeronNewsMetricMapper.Breakdown> performanceBreakdown(String dimension) {
        String groupBy = dimension == null ? "" : dimension.strip().toLowerCase();
        if (!GROUP_BY_KEYS.contains(groupBy)) {
            throw new HeronBusinessException(400, "metric_dimension_invalid",
                "只支持按 template / line / angle 汇总，收到：" + dimension);
        }
        return metrics.performanceBreakdown(groupBy);
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
