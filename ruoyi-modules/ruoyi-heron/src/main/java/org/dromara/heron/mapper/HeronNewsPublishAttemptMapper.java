package org.dromara.heron.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronNewsPublishAttempt;
import org.dromara.heron.domain.vo.NewsPublishAttemptVo;

import java.time.LocalDateTime;
import java.util.List;

public interface HeronNewsPublishAttemptMapper extends BaseMapperPlus<HeronNewsPublishAttempt, NewsPublishAttemptVo> {
    @Select("SELECT * FROM heron_news_publish_attempt WHERE active_candidate_id = #{candidateId} FOR UPDATE")
    HeronNewsPublishAttempt lockActive(@Param("candidateId") long candidateId);

    @Select("SELECT * FROM heron_news_publish_attempt WHERE id = #{id} FOR UPDATE")
    HeronNewsPublishAttempt lockById(@Param("id") long id);

    @Select("""
        <script>
        SELECT a.* FROM heron_news_publish_attempt a
        JOIN (SELECT version_id,MAX(id) AS id FROM heron_news_publish_attempt
          WHERE version_id IN
          <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          GROUP BY version_id) latest ON latest.id = a.attempt_id
        </script>
        """)
    List<HeronNewsPublishAttempt> latestForVersions(@Param("ids") List<Long> ids);

    @Update("""
        UPDATE heron_news_publish_attempt SET status=#{next},account=COALESCE(#{account},account),
          failure_code=#{failureCode},updated_at=#{now},resolved_by=#{resolvedBy},resolved_at=#{resolvedAt},
          resolution_reason=#{reason}
        WHERE id=#{id} AND status=#{expected}
        """)
    int transition(@Param("id") long id, @Param("expected") String expected, @Param("next") String next,
                   @Param("account") String account, @Param("failureCode") String failureCode,
                   @Param("now") LocalDateTime now, @Param("resolvedBy") Long resolvedBy,
                   @Param("resolvedAt") LocalDateTime resolvedAt, @Param("reason") String reason);
}
