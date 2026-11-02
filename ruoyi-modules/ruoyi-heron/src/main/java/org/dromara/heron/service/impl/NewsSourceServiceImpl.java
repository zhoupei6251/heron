package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.bo.NewsSourceBo;
import org.dromara.heron.domain.bo.NewsSourceQuery;
import org.dromara.heron.domain.vo.NewsSourceVo;
import org.dromara.heron.mapper.HeronNewsSourceMapper;
import org.dromara.heron.service.INewsSourceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.net.URI;
import java.util.UUID;

/**
 * 来源维护与采集编排。抓取与入库都不在同一个事务里：租约先落地，失败原因必须能独立提交，
 * 否则异常回滚会把排查所需的错误码一起抹掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsSourceServiceImpl implements INewsSourceService {

    /** 租约 2 分钟：单跳最长 10 秒、最多 4 跳，正常采集远不到期；到期后允许被别的实例接管。 */
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int DUE_BATCH = 2;
    private static final int DEFAULT_INTERVAL_SECONDS = 600;

    private final HeronNewsSourceMapper sources;
    private final NewsSourceFetcher fetcher;
    private final NewsCandidateCollector collector;
    private final HeronAccessService access;
    private final ObjectProvider<Clock> clocks;

    @Override
    public PageResult<NewsSourceVo> list(NewsSourceQuery query) {
        access.requirePermission("heron:newsSource:list");
        var wrapper = QueryBuilder.lambda(HeronNewsSource.class)
            .likeIfText(HeronNewsSource::getName, query.getName())
            .eqIfText(HeronNewsSource::getSourceType, query.getSourceType())
            .eqIfText(HeronNewsSource::getEnabled, query.getEnabled())
            .orderByDesc(HeronNewsSource::getSourceId).build();
        Page<NewsSourceVo> page = sources.selectVoPage(query.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public NewsSourceVo query(long sourceId) {
        access.requirePermission("heron:newsSource:query");
        NewsSourceVo vo = sources.selectVoById(sourceId);
        if (vo == null) throw new HeronAccessException();
        return vo;
    }

    @Override
    public void add(NewsSourceBo bo) {
        access.requirePermission("heron:newsSource:add");
        String url = validatedUrl(bo.getUrl());
        LocalDateTime now = now();
        long actor = access.requireActor();
        HeronNewsSource source = MapstructUtils.convert(bo, HeronNewsSource.class);
        source.setSourceId(IdUtil.getSnowflakeNextId());
        source.setUrl(url);
        source.setFailureCount(0);
        source.setNextFetchAt(now);
        source.setCreateBy(actor);
        source.setUpdateBy(actor);
        source.setCreateTime(now);
        source.setUpdateTime(now);
        if (sources.insert(source) != 1) throw new HeronBusinessException(409, "news_source_write", "来源保存失败");
    }

    @Override
    public void edit(NewsSourceBo bo) {
        access.requirePermission("heron:newsSource:edit");
        String url = validatedUrl(bo.getUrl());
        HeronNewsSource source = required(bo.getSourceId());
        source.setName(bo.getName());
        source.setSourceType(bo.getSourceType());
        source.setUrl(url);
        source.setIntervalSeconds(bo.getIntervalSeconds());
        source.setEnabled(bo.getEnabled());
        source.setUpdateBy(access.requireActor());
        source.setUpdateTime(now());
        if (sources.updateById(source) != 1) throw new HeronBusinessException(409, "news_source_write", "来源保存失败");
    }

    @Override
    public void remove(long sourceId) {
        access.requirePermission("heron:newsSource:remove");
        required(sourceId);
        if (sources.deleteById(sourceId) != 1) throw new HeronBusinessException(409, "news_source_write", "来源删除失败");
    }

    @Override
    public void fetch(long sourceId) {
        access.requirePermission("heron:newsSource:fetch");
        attempt(required(sourceId));
    }

    @Override
    public int collectDue() {
        LocalDateTime now = now();
        int collected = 0;
        for (long sourceId : sources.dueSourceIds(now, DUE_BATCH)) {
            HeronNewsSource source = sources.selectById(sourceId);
            if (source == null) continue;
            try {
                if (attempt(source)) collected++;
            } catch (RuntimeException e) {
                // 失败原因已按令牌写入来源行；一个坏来源不能拖垮整轮调度。
                log.warn("定时采集失败: sourceId={} code={}", sourceId, code(e));
            }
        }
        return collected;
    }

    /** 领取租约→抓取→入库定稿；任何一步失败都释放租约并把错误码写回来源，再把异常原样抛给调用方。 */
    private boolean attempt(HeronNewsSource source) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = now();
        if (sources.claim(source.getSourceId(), token, now, now.plus(LEASE)) != 1) {
            HeronNewsSource current = required(source.getSourceId());
            if (current.getFetchToken() != null && (current.getFetchLeaseUntil() == null
                || current.getFetchLeaseUntil().isAfter(now))) {
                throw new HeronBusinessException(409, "news_fetch_running", "该来源正在采集");
            }
            throw new HeronBusinessException(409, "news_source_disabled", "来源已停用，不能采集");
        }
        try {
            collector.collect(source.getSourceId(), token, fetcher.fetch(source));
            return true;
        } catch (RuntimeException e) {
            int failures = (source.getFailureCount() == null ? 0 : source.getFailureCount()) + 1;
            sources.recordFailure(source.getSourceId(), token, code(e), reason(e), now, nextRetryAt(source, failures, now));
            throw e;
        }
    }

    /** 失败退避 1/5/15 分钟，之后回到来源自身周期。 */
    private static LocalDateTime nextRetryAt(HeronNewsSource source, int failures, LocalDateTime failedAt) {
        int interval = source.getIntervalSeconds() == null ? DEFAULT_INTERVAL_SECONDS : source.getIntervalSeconds();
        return switch (failures) {
            case 1 -> failedAt.plusMinutes(1);
            case 2 -> failedAt.plusMinutes(5);
            case 3 -> failedAt.plusMinutes(15);
            default -> failedAt.plusSeconds(interval);
        };
    }

    private HeronNewsSource required(long sourceId) {
        HeronNewsSource source = sources.selectById(sourceId);
        if (source == null) throw new HeronAccessException();
        return source;
    }

    /** 保存时只做结构校验并归一；能不能出网由采集时的白名单与地址策略裁决。 */
    private String validatedUrl(String url) {
        URI uri = NewsUrlPolicy.normalize(url);
        if (NewsUrlPolicy.isIpLiteral(NewsUrlPolicy.hostOf(uri))) {
            throw new HeronBusinessException(400, "news_source_url", "来源必须使用域名，便于白名单校验");
        }
        return uri.toString();
    }

    private static String code(RuntimeException e) {
        return e instanceof HeronBusinessException business ? business.getCode() : "news_source_error";
    }

    private static String reason(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clocks.getIfAvailable(Clock::systemUTC));
    }
}
