package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 发布库存池：所有视频线的成片出口。
 *
 * <p>评估状态（curation）与发布状态（publish）是两个维度：一条片子可以
 * 合格（eligible）而已发布（published），也可以被淘汰（rejected）但文件
 * 保留。淘汰只改状态，不删产物与历史。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_publish_inventory")
public class HeronPublishInventory extends BaseEntity {

    public static final String CURATION_PENDING = "pending";
    public static final String CURATION_ELIGIBLE = "eligible";
    public static final String CURATION_HOLD = "hold";
    public static final String CURATION_REJECTED = "rejected";
    public static final String CURATION_EXPIRED = "expired";

    public static final String PUBLISH_WAITING = "waiting";
    public static final String PUBLISH_RESERVED = "reserved";
    public static final String PUBLISH_PUBLISHING = "publishing";
    public static final String PUBLISH_UNKNOWN = "unknown";
    public static final String PUBLISH_PUBLISHED = "published";
    public static final String PUBLISH_WITHDRAWN = "withdrawn";
    public static final String PUBLISH_SUPERSEDED = "superseded";

    @TableId private Long id;
    private Long versionId;
    private Long workId;
    private Long candidateId;
    private String bizLine;
    private Long durationMs;
    private String curationStatus;
    private String publishStatus;
    private Integer priorityScore;
    private String scoreBreakdown;
    private String curationReason;
    private String curatorVersion;
    private java.time.LocalDateTime evaluatedAt;
    private java.time.LocalDateTime expiresAt;
    private java.time.LocalDateTime reservedAt;
    private Long publishAttemptId;
}
