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
import org.dromara.heron.service.INewsMetricService;
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
}
