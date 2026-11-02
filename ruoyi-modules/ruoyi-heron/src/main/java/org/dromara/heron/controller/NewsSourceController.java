package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.NewsSourceBo;
import org.dromara.heron.domain.bo.NewsSourceQuery;
import org.dromara.heron.domain.vo.NewsSourceVo;
import org.dromara.heron.service.INewsSourceService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 新闻来源维护与手动采集。这里只接受已登记来源的 id，不接受任意 URL，
 * 出网目标仍由部署白名单与地址策略在采集时裁决。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/newsSource")
public class NewsSourceController extends BaseController {
    private final INewsSourceService sources;

    @SaCheckPermission("heron:newsSource:list")
    @GetMapping("/list")
    public R<PageResult<NewsSourceVo>> list(NewsSourceQuery query) { return R.ok(sources.list(query)); }

    @SaCheckPermission("heron:newsSource:query")
    @GetMapping("/{id}")
    public R<NewsSourceVo> get(@PathVariable long id) { return R.ok(sources.query(id)); }

    @SaCheckPermission("heron:newsSource:add")
    @RepeatSubmit
    @Log(title = "新闻来源", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping
    public R<Void> add(@RequestBody @Valid NewsSourceBo bo) {
        sources.add(bo);
        return R.ok();
    }

    /** 编辑同时校验默认组与 EditGroup：只传一个组会静默跳过另一组的约束。 */
    @SaCheckPermission("heron:newsSource:edit")
    @RepeatSubmit
    @Log(title = "新闻来源", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PutMapping
    public R<Void> edit(@RequestBody @Validated({Default.class, EditGroup.class}) NewsSourceBo bo) {
        sources.edit(bo);
        return R.ok();
    }

    @SaCheckPermission("heron:newsSource:remove")
    @Log(title = "新闻来源", businessType = BusinessType.DELETE, isSaveRequestData = false, isSaveResponseData = false)
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable long id) {
        sources.remove(id);
        return R.ok();
    }

    /** 手动采集是短事务外的受控出网，同来源并发由租约 CAS 挡下。 */
    @SaCheckPermission("heron:newsSource:fetch")
    @Log(title = "采集新闻来源", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/fetch")
    public R<Void> fetch(@PathVariable long id) {
        sources.fetch(id);
        return R.ok();
    }
}
