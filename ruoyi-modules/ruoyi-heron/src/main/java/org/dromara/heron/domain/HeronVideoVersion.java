package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

/** 不可变版本输入与一次性生成结果；审核、清理为独立后续事实。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_video_version")
public class HeronVideoVersion extends BaseEntity {
    @TableId private Long versionId;
    private Long workId;
    private Long ownerId;
    private Integer versionNo;
    private Long taskId;
    private String inputSnapshot;
    private String script;
    private String artifactManifest;
    private String checks;
    private Long durationMs;
    private String reviewStatus;
    private Long reviewBy;
    private LocalDateTime reviewAt;
    private String reviewReason;
    private String reviewContactSha;
    private String reviewVideoSha;
    private LocalDateTime cleanupStartedAt;
    private LocalDateTime cleanedAt;
}
