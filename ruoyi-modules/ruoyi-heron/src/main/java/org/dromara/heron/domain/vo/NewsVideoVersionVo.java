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
    private Long id;
    private Long workId;
    private Long ownerId;
    private Integer versionNo;
    private Long taskId;
    private String inputSnapshot;
    private String script;
    /** 原比例封面预览地址；null 表示该版本没有封面。 */
    private String coverUrl;
    private String checks;
    /** AI 审稿结论；自动发布只认这里的 PASS，null 表示没审过。 */
    private String editorialReview;
    /** 质量审稿报告；null 表示没做过 L3 审稿，不阻塞发布。 */
    private String qualityReport;
    /** 内容属性补充；null 或字段缺失时归因按「未记录」处理。 */
    private String contentProfile;
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
    private NewsPublishAttemptVo publishAttempt;
    /** 只在审片队列里填：跨作品列表需要作品标题，单作品视图不重复给。 */
    private String workTitle;
}
