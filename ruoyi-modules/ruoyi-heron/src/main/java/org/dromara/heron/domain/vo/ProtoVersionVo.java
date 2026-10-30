package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronProtoVersion;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 历史版本视图。文件只给逻辑 ID/hash；状态实时关联 SP1 task。 */
@Data
@AutoMapper(target = HeronProtoVersion.class)
public class ProtoVersionVo implements Serializable {
    private Long versionId;
    private Long projectId;
    private Long ownerId;
    private Integer versionNo;
    private Long taskId;
    private String status;
    private String engineCommit;
    private String reviewStatus;
    private Long reviewBy;
    private LocalDateTime reviewAt;
    private String reviewReason;
    private String reviewPreviewSha;
    private String reviewRpSha;
    private LocalDateTime createTime;
    private InputSnapshot input;
    private List<ProductionFileVo> artifacts;
    private ManifestEvidence audit;
    private List<ProtoNativeVerificationVo> nativeVerifications;
    private ProtoDeliveryVo delivery;

    /** 冻结的服务器白名单快照，templates 只有逻辑名与 sha256。 */
    public record InputSnapshot(long projectId, int versionNo, long revision, String requirement,
                                String canvas, String themeKey, String engineCommit,
                                Map<String, String> templateManifest, List<String> supportedWidgets,
                                String modelConfigId) { }

    /** 固定流水线的校验结果，不包含引擎原文或绝对路径。 */
    public record ManifestEvidence(String engineCommit, Map<String, String> templateManifest,
                                   List<PageEvidence> pages, List<String> rp1PageIds,
                                   String rp1ArtifactId, String previewSummaryArtifactId,
                                   String nativeRpArtifactId, String checkArtifactId,
                                   Boolean structuralOk, Boolean nativeFormatChecked,
                                   String sourceRp1Sha256, AuditEvidence audit) { }

    public record PageEvidence(String pageId, String previewArtifactId) { }

    /** Boolean 避免缺字段被默认通过。 */
    public record AuditEvidence(Boolean colorPassed, Boolean fontPassed, Boolean semanticNamePassed,
                                Boolean groupingPassed, String rawAuditArtifactId) {
        public boolean passed() {
            return Boolean.TRUE.equals(colorPassed) && Boolean.TRUE.equals(fontPassed)
                && Boolean.TRUE.equals(semanticNamePassed) && Boolean.TRUE.equals(groupingPassed);
        }
    }
}
