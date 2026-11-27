package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronPublishInventory;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 待发布池安全视图：不暴露工作目录与产物路径。 */
@Data
@AutoMapper(target = HeronPublishInventory.class)
public class PublishInventoryVo implements Serializable {
    private Long id;
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
    private LocalDateTime evaluatedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime reservedAt;
    /** 原比例封面预览地址；取自版本，null 表示无封面。 */
    private String coverUrl;
}
