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
import org.dromara.heron.service.INewsWorkService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 新闻作品、版本、审核和受保护文件；按 ID 的权限由业务层统一返回真实 404。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/newsWork")
public class NewsWorkController extends BaseController {
    private final INewsWorkService news;

    @SaCheckPermission("heron:newsWork:list")
    @GetMapping("/list")
    public R<PageResult<NewsWorkVo>> list(NewsWorkQuery query) { return R.ok(news.queryPageList(query)); }

    @GetMapping("/{id}")
    public R<NewsWorkVo> get(@PathVariable long id) { return R.ok(news.queryById(id)); }

    @RepeatSubmit
    @Log(title = "新闻草稿", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PutMapping("/{id}/draft")
    public R<NewsWorkVo> draft(@PathVariable long id, @RequestBody NewsDraftBo bo) { return R.ok(news.saveDraft(id, bo)); }

    @Log(title = "新闻生成", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/generate")
    public R<ProductionReceiptVo> generate(@PathVariable long id, @RequestBody NewsGenerateBo bo) { return R.ok(news.generate(id, bo)); }

    @GetMapping("/{id}/versions")
    public R<PageResult<NewsVideoVersionVo>> versions(@PathVariable long id, PageQuery page) { return R.ok(news.versions(id, page)); }

    @GetMapping("/{id}/versions/{versionId}")
    public R<NewsVideoVersionVo> version(@PathVariable long id, @PathVariable long versionId) { return R.ok(news.version(id, versionId)); }

    @Log(title = "新闻审片", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/versions/{versionId}/review")
    public R<NewsVideoVersionVo> review(@PathVariable long id, @PathVariable long versionId, @RequestBody NewsReviewBo bo) {
        return R.ok(news.review(id, versionId, bo));
    }

    @GetMapping("/{id}/versions/{versionId}/preview/{artifactId}")
    public void preview(@PathVariable long id, @PathVariable long versionId, @PathVariable String artifactId,
                        HttpServletResponse response) throws IOException {
        try (NewsFileVo file = news.preview(id, versionId, artifactId)) { stream(file, response, false); }
    }

    @Log(title = "新闻正式下载", businessType = BusinessType.EXPORT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/versions/{versionId}/download")
    public void download(@PathVariable long id, @PathVariable long versionId, @RequestParam String artifactId,
                         @RequestParam String videoSha256, HttpServletResponse response) throws IOException {
        try (NewsFileVo file = news.download(id, versionId, artifactId, videoSha256)) { stream(file, response, true); }
    }

    private void stream(NewsFileVo file, HttpServletResponse response, boolean attachment) throws IOException {
        response.setContentType(file.mediaType());
        response.setContentLengthLong(file.bytes());
        response.setHeader("Content-Disposition", (attachment ? "attachment" : "inline") + "; filename=\"" + file.fileName() + "\"");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-SHA256", file.sha256());
        response.setHeader("X-Content-Type-Options", "nosniff");
        file.content().transferTo(response.getOutputStream());
    }
}
