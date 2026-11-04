package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 新闻作品、版本、审核和受保护文件；按 ID 的权限由业务层统一返回真实 404。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/newsWork")
public class NewsWorkController extends BaseController {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d{0,18})-(\\d{0,18})");
    private static final long[] UNSATISFIED = {-1, -1};
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

    @Log(title = "人工发布回执", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/versions/{versionId}/publish")
    public R<NewsVideoVersionVo> publish(@PathVariable long id, @PathVariable long versionId, @RequestBody NewsPublishBo bo) {
        return R.ok(news.publish(id, versionId, bo));
    }

    @GetMapping("/{id}/versions/{versionId}/preview/{artifactId}")
    public void preview(@PathVariable long id, @PathVariable long versionId, @PathVariable String artifactId,
                        HttpServletRequest request, HttpServletResponse response) throws IOException {
        try (NewsFileVo file = news.preview(id, versionId, artifactId)) { stream(file, response, false, request); }
    }

    @Log(title = "新闻正式下载", businessType = BusinessType.EXPORT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/versions/{versionId}/download")
    public void download(@PathVariable long id, @PathVariable long versionId, @RequestParam String artifactId,
                         @RequestParam String videoSha256, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        try (NewsFileVo file = news.download(id, versionId, artifactId, videoSha256)) { stream(file, response, true, request); }
    }

    /**
     * 带 Range 时必须回 206 + Content-Range，否则审片的 mp4 拖不动进度条；没有 Range 头时与原来一样回完整体。
     * 归属与哈希已由业务层校验过，这里只搬运区间。
     */
    private void stream(NewsFileVo file, HttpServletResponse response, boolean attachment,
                        HttpServletRequest request) throws IOException {
        long total = file.bytes();
        long[] range = rangeOf(request.getHeader("Range"), total);
        if (range == UNSATISFIED) {
            unsatisfiable(response, total);
            return;
        }
        response.setContentType(file.mediaType());
        response.setContentLengthLong(total);
        response.setHeader("Content-Disposition", (attachment ? "attachment" : "inline") + "; filename=\"" + file.fileName() + "\"");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-SHA256", file.sha256());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Accept-Ranges", "bytes");
        if (range == null) {
            file.content().transferTo(response.getOutputStream());
            return;
        }
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        long start = range[0];
        long end = range[1];
        // shortcut: 对象存储模式下这里从 0 起跳读，远端 Range 会把前缀重新拉一遍（实测 7 MB 片段的末 500 B 约 4 秒）。
        // 前端目前取整段 blob 播放，走不到这条分支；要做断点续传就改成带 range 的 GetObject。
        response.setContentLengthLong(end - start + 1);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        InputStream input = file.content();
        input.skipNBytes(start);
        OutputStream output = response.getOutputStream();
        byte[] buffer = new byte[8192];
        long remaining = end - start + 1;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        output.flush();
    }

    private static void unsatisfiable(HttpServletResponse response, long total) {
        response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
        response.setHeader("Content-Range", "bytes */" + total);
    }

    /** 单段闭区间：null 表示回整段，UNSATISFIED 表示 416。多段等不认识的写法按无 Range 处理。 */
    static long[] rangeOf(String header, long total) {
        Matcher range = header == null ? null : RANGE.matcher(header);
        if (range == null || !range.matches()) {
            return null;
        }
        String from = range.group(1);
        String to = range.group(2);
        if (from.isEmpty() && to.isEmpty()) {
            return UNSATISFIED;
        }
        long start;
        long end;
        if (from.isEmpty()) {
            // bytes=-N 是末尾 N 字节，不是从 N 开始。
            start = Math.max(0, total - Long.parseLong(to));
            end = total - 1;
        } else {
            start = Long.parseLong(from);
            end = to.isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(to));
        }
        return start > end || start >= total ? UNSATISFIED : new long[] {start, end};
    }
}
