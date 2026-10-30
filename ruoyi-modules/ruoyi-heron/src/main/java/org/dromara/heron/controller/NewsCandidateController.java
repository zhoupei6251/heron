package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.NewsCandidateBo;
import org.dromara.heron.domain.bo.NewsCandidateQuery;
import org.dromara.heron.domain.vo.NewsCandidateVo;
import org.dromara.heron.domain.vo.NewsWorkVo;
import org.dromara.heron.service.INewsWorkService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 团队新闻输入与认领；来源 CRUD 留给 N2，不开放任意 URL 抓取。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/newsCandidate")
public class NewsCandidateController extends BaseController {
    private final INewsWorkService news;

    @SaCheckPermission("heron:newsCandidate:list")
    @GetMapping("/list")
    public R<PageResult<NewsCandidateVo>> list(NewsCandidateQuery query) { return R.ok(news.candidates(query)); }

    @GetMapping("/{id}")
    public R<NewsCandidateVo> get(@PathVariable long id) { return R.ok(news.candidate(id)); }

    @SaCheckPermission("heron:newsCandidate:manual")
    @RepeatSubmit
    @Log(title = "手工新闻候选", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/manual")
    public R<NewsCandidateVo> manual(@RequestBody NewsCandidateBo bo) { return R.ok(news.addManual(bo)); }

    /** 持久认领语义允许同用户重放，不由短期防重缓存提前拒绝。 */
    @Log(title = "认领新闻候选", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/{id}/claim")
    public R<NewsWorkVo> claim(@PathVariable long id) { return R.ok(news.claim(id)); }
}
