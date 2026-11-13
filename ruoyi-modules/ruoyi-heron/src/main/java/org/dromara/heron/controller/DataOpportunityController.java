package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.vo.DataOpportunityVo;
import org.dromara.heron.service.impl.DataOpportunityService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据机会：第一步只读 + 手动触发分析 + 人工淘汰。
 *
 * <p>权限沿用数据集读取权限：这一步还没有独立页面，等第二步接入决策工作台时
 * 再补 dataOpportunity 自己的权限与菜单种子，届时需要新的迁移。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/dataOpportunity")
public class DataOpportunityController extends BaseController {

    private final DataOpportunityService opportunities;

    @SaCheckPermission("heron:dataSource:list")
    @GetMapping("/list")
    public R<PageResult<DataOpportunityVo>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String sourceKey,
                                                 PageQuery pageQuery) {
        return R.ok(opportunities.list(status, sourceKey, pageQuery));
    }

    /** 手动触发一次全量分析；定时任务默认关闭，第一步靠这个入口看真实效果。 */
    @SaCheckPermission("heron:dataSource:list")
    @RepeatSubmit
    @Log(title = "数据机会分析", businessType = BusinessType.OTHER, isSaveRequestData = false)
    @PostMapping("/analyze")
    public R<DataOpportunityService.Report> analyze(@RequestParam(required = false) Integer windowPoints) {
        return R.ok(windowPoints == null ? opportunities.analyze() : opportunities.analyze(windowPoints));
    }

    @SaCheckPermission("heron:dataSource:list")
    @Log(title = "数据机会淘汰", businessType = BusinessType.UPDATE)
    @PostMapping("/{opportunityId}/reject")
    public R<Void> reject(@PathVariable long opportunityId, @RequestParam(required = false) String reason) {
        opportunities.reject(opportunityId, reason);
        return R.ok();
    }
}
