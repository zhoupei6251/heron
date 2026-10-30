package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.*;
import org.dromara.heron.domain.vo.*;
import org.dromara.heron.service.IProtoProjectService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 原型业务入口。按 ID 的请求由 service 统一核验范围与动作，越权返回真实 404。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/proto")
public class ProtoController extends BaseController {
    private final IProtoProjectService proto;

    @SaCheckPermission("heron:proto:list")
    @GetMapping("/list")
    public R<PageResult<ProtoProjectVo>> list(ProtoProjectQuery query, PageQuery page) {
        return R.ok(proto.queryPageList(query, page));
    }

    @GetMapping("/{projectId}")
    public R<ProtoProjectVo> project(@PathVariable long projectId) {
        return R.ok(proto.queryById(projectId));
    }

    @SaCheckPermission("heron:proto:add")
    @RepeatSubmit
    @Log(title = "原型项目", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping({"", "/"})
    public R<ProtoProjectVo> create(@RequestBody ProtoProjectBo bo) {
        return R.ok(proto.insertByBo(bo));
    }

    @RepeatSubmit
    @Log(title = "原型项目", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PutMapping("/{projectId}")
    public R<ProtoProjectVo> edit(@PathVariable long projectId, @RequestBody ProtoProjectBo bo) {
        return R.ok(proto.updateByBo(projectId, bo));
    }

    @Log(title = "原型归档", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/archive")
    public R<ProtoProjectVo> archive(@PathVariable long projectId) {
        return R.ok(proto.archive(projectId));
    }

    @Log(title = "原型生产", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/generate")
    public R<ProductionReceiptVo> generate(@PathVariable long projectId, @RequestBody ProtoGenerateBo bo) {
        return R.ok(proto.generate(projectId, bo));
    }

    @GetMapping("/{projectId}/versions")
    public R<PageResult<ProtoVersionVo>> versions(@PathVariable long projectId, PageQuery page) {
        return R.ok(proto.versions(projectId, page));
    }

    @GetMapping("/{projectId}/versions/{versionId}")
    public R<ProtoVersionVo> version(@PathVariable long projectId, @PathVariable long versionId) {
        return R.ok(proto.version(projectId, versionId));
    }

    @Log(title = "原型审核", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/versions/{versionId}/review")
    public R<ProtoVersionVo> review(@PathVariable long projectId, @PathVariable long versionId,
                                    @RequestBody ProtoReviewBo bo) {
        return R.ok(proto.review(projectId, versionId, bo));
    }

    @Log(title = "原型原生验证", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/versions/{versionId}/native-verifications")
    public R<ProtoNativeVerificationVo> verify(@PathVariable long projectId, @PathVariable long versionId,
                                               @RequestBody ProtoNativeVerificationBo bo) {
        return R.ok(proto.recordNativeVerification(projectId, versionId, bo));
    }

    @Log(title = "原型交付", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/versions/{versionId}/deliver")
    public R<ProtoDeliveryVo> deliver(@PathVariable long projectId, @PathVariable long versionId,
                                     @RequestBody ProtoDeliverBo bo) {
        return R.ok(proto.deliver(projectId, versionId, bo));
    }

    @GetMapping("/{projectId}/versions/{versionId}/preview/{artifactId}")
    public void preview(@PathVariable long projectId, @PathVariable long versionId, @PathVariable String artifactId,
                        HttpServletResponse response) throws IOException {
        stream(proto.preview(projectId, versionId, artifactId), response, true);
    }

    @Log(title = "原型审核下载", businessType = BusinessType.EXPORT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/versions/{versionId}/review-download")
    public void reviewDownload(@PathVariable long projectId, @PathVariable long versionId,
                               @RequestParam String artifactId, HttpServletResponse response) throws IOException {
        stream(proto.reviewDownload(projectId, versionId, artifactId), response, false);
    }

    @Log(title = "原型正式下载", businessType = BusinessType.EXPORT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{projectId}/versions/{versionId}/download")
    public void download(@PathVariable long projectId, @PathVariable long versionId, @RequestParam String artifactId,
                         @RequestParam String rpSha256, HttpServletResponse response) throws IOException {
        stream(proto.download(projectId, versionId, artifactId, rpSha256), response, false);
    }

    private static void stream(IProtoProjectService.ProtoFile file, HttpServletResponse response, boolean inline)
        throws IOException {
        try (file) {
            response.setContentType(file.mediaType());
            response.setContentLengthLong(file.bytes());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Content-SHA256", file.sha256());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(file.fileName()).build().toString());
            file.content().transferTo(response.getOutputStream());
        }
    }
}
