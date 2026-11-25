package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.domain.TrendLearnState;
import org.dromara.heron.domain.vo.TrendVideoVo;

import java.util.List;

/** 抖音热点候选视频；学习状态写回必须带期望状态条件。 */
public interface HeronTrendVideoMapper extends BaseMapperPlus<HeronTrendVideo, TrendVideoVo> {

    List<TrendLearnState> RECOVERABLE_STATES = List.of(TrendLearnState.SEEN, TrendLearnState.SELECTED,
        TrendLearnState.DOWNLOADED, TrendLearnState.DECOMPOSED, TrendLearnState.DOWNLOAD_FAILED,
        TrendLearnState.DECOMPOSE_FAILED, TrendLearnState.LEARN_FAILED);

    default List<String> selectLearnedAwemeIds() {
        return selectList(QueryBuilder.lambda(HeronTrendVideo.class)
            .select(HeronTrendVideo::getAwemeId)
            .eq(HeronTrendVideo::getLearnState, TrendLearnState.LEARNED.name())
            .build()).stream().map(HeronTrendVideo::getAwemeId).toList();
    }

    default List<HeronTrendVideo> selectLearnCandidates(long maxDurationMs) {
        var query = QueryBuilder.lambda(HeronTrendVideo.class)
            .isNotNull(HeronTrendVideo::getDurationMs)
            .in(HeronTrendVideo::getLearnState, RECOVERABLE_STATES.stream().map(Enum::name).toList())
            .apply("(COALESCE(digg_count, 0) + COALESCE(comment_count, 0) + "
                + "COALESCE(share_count, 0) + COALESCE(collect_count, 0)) > 0")
            .orderByDesc(HeronTrendVideo::getCollectedAt)
            .orderByAsc(HeronTrendVideo::getRankNo);
        if (maxDurationMs > 0) {
            query.le(HeronTrendVideo::getDurationMs, maxDurationMs);
        }
        return selectList(query.last("LIMIT 50").build());
    }

    default int backfillOssId(long id, long ossId, String token) {
        return lambda().set(HeronTrendVideo::getOssId, ossId)
            .eq(HeronTrendVideo::getId, id)
            .ne(HeronTrendVideo::getLearnState, TrendLearnState.LEARNED.name())
            .like(HeronTrendVideo::getLearnNote, "%token=" + token + ";%").updateCount();
    }

    default int markSelected(long id, TrendLearnState expected, String expectedNote, String note) {
        var update = lambda().set(HeronTrendVideo::getLearnState, TrendLearnState.SELECTED.name())
            .set(HeronTrendVideo::getLearnNote, note)
            .eq(HeronTrendVideo::getId, id)
            .eq(HeronTrendVideo::getLearnState, expected.name());
        if (expectedNote == null) {
            update.isNull(HeronTrendVideo::getLearnNote);
        } else {
            update.eq(HeronTrendVideo::getLearnNote, expectedNote);
        }
        return update.updateCount();
    }

    default int markDownloaded(long id, long ossId, String note, String token) {
        return lambda().set(HeronTrendVideo::getLearnState, TrendLearnState.DOWNLOADED.name())
            .set(HeronTrendVideo::getOssId, ossId)
            .set(HeronTrendVideo::getLearnNote, note)
            .eq(HeronTrendVideo::getId, id)
            .eq(HeronTrendVideo::getLearnState, TrendLearnState.SELECTED.name())
            .like(HeronTrendVideo::getLearnNote, "%token=" + token + ";%").updateCount();
    }

    default int markDecomposed(long id, TrendLearnState expected, String note, String token) {
        return lambda().set(HeronTrendVideo::getLearnState, TrendLearnState.DECOMPOSED.name())
            .set(HeronTrendVideo::getLearnNote, note)
            .eq(HeronTrendVideo::getId, id)
            .eq(HeronTrendVideo::getLearnState, expected.name())
            .like(HeronTrendVideo::getLearnNote, "%token=" + token + ";%").updateCount();
    }

    default int markLearned(long id, TrendLearnState expected, String note, String token) {
        return lambda().set(HeronTrendVideo::getLearnState, TrendLearnState.LEARNED.name())
            .set(HeronTrendVideo::getLearnNote, note)
            .eq(HeronTrendVideo::getId, id)
            .eq(HeronTrendVideo::getLearnState, expected.name())
            .like(HeronTrendVideo::getLearnNote, "%token=" + token + ";%").updateCount();
    }

    default int markFailed(long id, TrendLearnState expected, TrendLearnState failed, String note, String token) {
        return lambda().set(HeronTrendVideo::getLearnState, failed.name())
            .set(HeronTrendVideo::getLearnNote, note)
            .eq(HeronTrendVideo::getId, id)
            .eq(HeronTrendVideo::getLearnState, expected.name())
            .like(HeronTrendVideo::getLearnNote, "%token=" + token + ";%").updateCount();
    }
}
