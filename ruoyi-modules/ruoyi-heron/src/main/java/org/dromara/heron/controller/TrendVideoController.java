package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronTrendHotword;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.vo.TrendHotwordVo;
import org.dromara.heron.domain.vo.TrendVideoVo;
import org.dromara.heron.mapper.HeronTrendHotwordMapper;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.dromara.heron.service.impl.TrendCollectService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 市面爆款候选：看采集到了什么，也能手动触发一次采集。
 *
 * <p>列表默认按热榜位次排——那是平台算过的热度序，不自己再排一次。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/trend")
public class TrendVideoController extends BaseController {

    private final HeronTrendVideoMapper videos;
    private final HeronTrendHotwordMapper hotwords;
    private final TrendCollectService collect;
    private final HeronNewsProperties news;

    /** 热度候选分页。onlyShort=true 只看够短的，够短才算可拆解的候选。 */
    @SaCheckPermission("heron:newsWork:query")
    @GetMapping("/videos")
    public R<PageResult<TrendVideoVo>> videos(PageQuery pageQuery,
                                              @RequestParam(defaultValue = "true") boolean onlyShort) {
        long maxDuration = news.getTrendMaxDurationMs();
        var lqw = QueryBuilder.lambda(HeronTrendVideo.class);
        if (onlyShort && maxDuration > 0) {
            lqw.and(w -> w.le(HeronTrendVideo::getDurationMs, maxDuration).or().isNull(HeronTrendVideo::getDurationMs));
        }
        lqw.orderByAsc(HeronTrendVideo::getCollectedAt).orderByAsc(HeronTrendVideo::getRankNo);
        var rows = videos.selectVoPage(pageQuery.build(), lqw.build());
        rows.getRecords().forEach(vo -> vo.setShortEnough(
            maxDuration <= 0 || vo.getDurationMs() == null || vo.getDurationMs() <= maxDuration));
        return R.ok(PageResult.build(rows.getRecords(), rows.getTotal()));
    }

    /** 热词分页：按最近一次采到的位次排，位次空的（置顶条）排最后。 */
    @SaCheckPermission("heron:newsWork:query")
    @GetMapping("/hotwords")
    public R<PageResult<TrendHotwordVo>> hotwords(PageQuery pageQuery) {
        var lqw = QueryBuilder.lambda(HeronTrendHotword.class);
        lqw.orderByDesc(HeronTrendHotword::getLastSeenAt);
        lqw.orderByAsc(HeronTrendHotword::getRankNo);
        var rows = hotwords.selectVoPage(pageQuery.build(), lqw.build());
        return R.ok(PageResult.build(rows.getRecords(), rows.getTotal()));
    }

    /** 手动采集一次；定时任务没开的时候也用它。 */
    @SaCheckPermission("heron:newsWork:publish")
    @Log(title = "抖音热点采集", businessType = BusinessType.INSERT)
    @PostMapping("/collect")
    public R<Integer> collect() {
        return R.ok(collect.collect());
    }
}
