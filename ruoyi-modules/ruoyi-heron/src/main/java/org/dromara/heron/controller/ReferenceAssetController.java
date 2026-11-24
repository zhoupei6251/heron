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
import org.dromara.heron.domain.bo.ReferenceAssetAuthorizeBo;
import org.dromara.heron.domain.bo.ReferenceAssetQueryBo;
import org.dromara.heron.domain.vo.ReferenceAssetVo;
import org.dromara.heron.service.IReferenceAssetService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 参考素材治理：查看状态、人工授权与受控下载。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/trend/assets")
public class ReferenceAssetController extends BaseController {

    private final IReferenceAssetService referenceAssets;

    @SaCheckPermission("heron:newsWork:query")
    @GetMapping("/list")
    public R<PageResult<ReferenceAssetVo>> list(ReferenceAssetQueryBo bo, PageQuery pageQuery) {
        return R.ok(referenceAssets.queryPageList(bo, pageQuery));
    }

    @SaCheckPermission("heron:newsWork:query")
    @GetMapping("/{id}")
    public R<ReferenceAssetVo> get(@PathVariable long id) {
        return R.ok(referenceAssets.queryById(id));
    }

    @SaCheckPermission("heron:newsWork:review")
    @Log(title = "检查参考素材", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/check")
    public R<ReferenceAssetVo> check(@PathVariable long id) {
        return R.ok(referenceAssets.check(id));
    }

    @SaCheckPermission("heron:newsWork:publish")
    @Log(title = "参考素材授权", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{id}/authorize")
    public R<ReferenceAssetVo> authorize(@PathVariable long id, @RequestBody @Validated ReferenceAssetAuthorizeBo bo) {
        return R.ok(referenceAssets.authorize(id, bo.allowed(), bo.reason()));
    }

    @SaCheckPermission("heron:newsWork:publish")
    @Log(title = "获取参考素材", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/{id}/acquire")
    public R<ReferenceAssetVo> acquire(@PathVariable long id) {
        return R.ok(referenceAssets.acquire(id));
    }
}
