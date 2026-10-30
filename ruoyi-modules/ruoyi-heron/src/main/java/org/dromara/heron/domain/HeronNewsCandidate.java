package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

/** 团队共享候选；候选是新闻事务的稳定锁锚点。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_news_candidate")
public class HeronNewsCandidate extends BaseEntity {
    @TableId private Long candidateId;
    private String title;
    private String summary;
    /** 有界证据 JSON：items 与 evidenceTruncated。 */
    private String evidence;
    private String dedupKey;
    private String normalizedTitle;
    private Long hitCount;
    private Long heatScore;
    private Integer bestRank;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Long claimedBy;
    private LocalDateTime claimedAt;
    private Long activeTaskId;
    /** 历史发布事实，只读；客户端不可设置。 */
    private LocalDateTime publishedAt;
}
