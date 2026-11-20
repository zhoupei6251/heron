package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.NewsMetricBo;
import org.dromara.heron.domain.vo.NewsMetricVo;
import org.dromara.heron.domain.vo.NewsPerformanceInsightVo;
import org.dromara.heron.domain.vo.StrategyReferenceVo;
import org.dromara.heron.service.INewsMetricService;
import org.dromara.heron.service.impl.StrategyReferenceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 发布表现回填：平台侧拿到的数据录进来，选题器会参考最近几期。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/metrics")
public class NewsMetricController extends BaseController {
    private final INewsMetricService metrics;
    private final StrategyReferenceService references;

    @SaCheckPermission("heron:newsWork:publish")
    @Log(title = "发布表现", businessType = BusinessType.INSERT)
    @PostMapping
    public R<NewsMetricVo> save(@RequestBody @Valid NewsMetricBo bo) {
        return R.ok(metrics.save(bo));
    }

    @SaCheckPermission("heron:newsWork:query")
    @GetMapping("/{versionId}")
    public R<List<NewsMetricVo>> byVersion(@PathVariable long versionId) {
        return R.ok(metrics.byVersion(versionId));
    }

    /**
     * 归因结论：按维度分桶，结论带证据分量。
     *
     * <p>中位数、样本等级、是否被单条极端值主导，都随结果一起给，前端不必自己猜。
     * 旧版只给均值的 breakdown 接口已经撤掉：同一件事留两个口径，
     * 迟早有人拿均值当结论。
     */
    @SaCheckPermission("heron:newsWork:list")
    @GetMapping("/insight")
    public R<NewsPerformanceInsightVo> insight(
        @RequestParam(defaultValue = "template") String dimension) {
        return R.ok(metrics.performanceInsight(dimension));
    }

    /**
     * 历史表现参考卡。
     *
     * <p>只给人看：这张卡不进提示词，也不自动改策略。
     * 被人工锁定的维度照常返回，但要带上锁定理由与期限，并且不给聚合结论。
     */
    @SaCheckPermission("heron:newsWork:list")
    @GetMapping("/reference")
    public R<StrategyReferenceVo> reference(
        @RequestParam(defaultValue = "template") String dimension,
        @RequestParam(required = false) String bucketValue) {
        return R.ok(references.reference(dimension, bucketValue));
    }
}
