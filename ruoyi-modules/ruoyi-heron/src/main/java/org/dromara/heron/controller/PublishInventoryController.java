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
import org.dromara.heron.domain.bo.PublishInventoryQuery;
import org.dromara.heron.domain.vo.PublishInventoryVo;
import org.dromara.heron.domain.vo.PublishReservationVo;
import org.dromara.heron.service.impl.PublishInventoryService;
import org.dromara.heron.service.impl.PublishScheduler;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 待发布池：成片库存的查询、评估结论与统一预占发布。
 *
 * <p>人工「立即发布」与自动调度共用同一条原子预占——并发点击只有一个成功。
 * 人工可以越过 AI 排序，但不能越过入池时的硬闸。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/publish/inventory")
public class PublishInventoryController extends BaseController {

    private final PublishInventoryService inventory;
    private final PublishScheduler scheduler;

    /** 待发布池：按评估状态/发布状态/产线过滤，默认视图是合格待发布。 */
    @SaCheckPermission("heron:publish:list")
    @GetMapping("/list")
    public R<PageResult<PublishInventoryVo>> list(PublishInventoryQuery query) {
        return R.ok(inventory.pageList(query));
    }

    /** 人工立即发布：原子预占后直接走与自动调度相同的发布链；预占失败说明已被抢先。 */
    @SaCheckPermission("heron:publish:publish")
    @RepeatSubmit
    @Log(title = "库存立即发布", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/reserve")
    public R<PublishReservationVo> reserve(@PathVariable long id) {
        return R.ok(scheduler.publishFromInventory(id));
    }
}
