package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.MediaAssetBo;
import org.dromara.heron.domain.bo.MediaAssetQueryBo;
import org.dromara.heron.domain.bo.MediaAssetSelectionBo;
import org.dromara.heron.domain.vo.MediaAssetSelectionVo;
import org.dromara.heron.domain.vo.MediaAssetVo;
import org.dromara.heron.service.IMediaAssetService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 素材入库与治理入口；场景选材供 V2 渲染链路调用。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/mediaAsset")
public class MediaAssetController extends BaseController {

    private final IMediaAssetService mediaAssets;

    @SaCheckPermission("heron:mediaAsset:list")
    @GetMapping("/list")
    public R<PageResult<MediaAssetVo>> list(MediaAssetQueryBo bo, PageQuery pageQuery) {
        return R.ok(mediaAssets.queryPageList(bo, pageQuery));
    }

    @SaCheckPermission("heron:mediaAsset:query")
    @GetMapping("/{id}")
    public R<MediaAssetVo> get(@PathVariable long id) {
        return R.ok(mediaAssets.queryById(id));
    }

    @SaCheckPermission("heron:mediaAsset:add")
    @Log(title = "素材入库", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<MediaAssetVo> add(@RequestPart("metadata") @Validated(AddGroup.class) MediaAssetBo bo,
                               @RequestPart("file") MultipartFile file) {
        return R.ok(mediaAssets.insertByBo(bo, file));
    }

    @SaCheckPermission("heron:mediaAsset:edit")
    @Log(title = "素材修改", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<MediaAssetVo> edit(@Validated(EditGroup.class) @RequestBody MediaAssetBo bo) {
        return R.ok(mediaAssets.updateByBo(bo));
    }

    @SaCheckPermission("heron:mediaAsset:remove")
    @Log(title = "素材删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空") @PathVariable Long[] ids) {
        return toAjax(mediaAssets.deleteWithValidByIds(List.of(ids)));
    }

    @SaCheckPermission("heron:mediaAsset:select")
    @PostMapping("/select")
    public R<MediaAssetSelectionVo> select(@Validated @RequestBody MediaAssetSelectionBo request) {
        return R.ok(mediaAssets.selectForScene(request));
    }
}
