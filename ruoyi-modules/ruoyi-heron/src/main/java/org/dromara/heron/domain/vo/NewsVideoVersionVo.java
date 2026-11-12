package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronVideoVersion;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** 版本安全视图：artifactManifest 不映射，使用无 storageKey 的 artifacts。 */
@Data
@AutoMapper(target = HeronVideoVersion.class)
public class NewsVideoVersionVo implements Serializable {
    private Long versionId;
    private Long workId;
    private Long ownerId;
    private Integer versionNo;
    private Long taskId;
    private String inputSnapshot;
    private String script;
    private String checks;
    private Long durationMs;
    private String reviewStatus;
    private Long reviewBy;
    private LocalDateTime reviewAt;
    private String reviewReason;
    private String reviewContactSha;
    private String reviewVideoSha;
    private String publishSource;
    private String publishUrl;
    private String publishAccount;
    private LocalDateTime publishAt;
    private Long publishBy;
    private String publishVideoSha;
    private LocalDateTime cleanupStartedAt;
    private LocalDateTime cleanedAt;
    private LocalDateTime createTime;
    private String taskStatus;
    private String taskErrorCode;
    private String taskErrorReason;
    private List<ProductionFileVo> artifacts;
    /** 只在审片队列里填：跨作品列表需要作品标题，单作品视图不重复给。 */
    private String workTitle;
}
