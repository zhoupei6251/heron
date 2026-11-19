package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronNewsPublishAttempt;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 仅在所属作品可读后返回，不暴露上传日志或文件路径。 */
@Data
@AutoMapper(target = HeronNewsPublishAttempt.class)
public class NewsPublishAttemptVo implements Serializable {
    private Long attemptId;
    private Long versionId;
    private String videoSha;
    private String source;
    private String account;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private String failureCode;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolutionReason;
}
