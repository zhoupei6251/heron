package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 与已发布事实分开记录不可回滚的外部尝试；候选级唯一活动尝试由数据库兜底。 */
@Data
@TableName("heron_news_publish_attempt")
public class HeronNewsPublishAttempt {
    @TableId private Long attemptId;
    private Long candidateId;
    private Long workId;
    private Long versionId;
    private String videoSha;
    private String source;
    private String account;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private Long actorId;
    private String failureCode;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionReason;
}
