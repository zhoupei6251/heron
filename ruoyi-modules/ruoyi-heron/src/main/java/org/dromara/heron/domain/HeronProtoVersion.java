package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/** 不可变输入/产物与独立审核、原生验证、交付事实；不复制任务执行状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_proto_version")
public class HeronProtoVersion extends BaseEntity {
    @TableId
    private Long versionId;
    private Long projectId;
    private Long ownerId;
    private Integer versionNo;
    private Long taskId;
    private String inputSnapshot;
    private String artifactManifest;
    private String engineCommit;
    private String templateManifest;
    private String auditResult;
    private String reviewStatus;
    private Long reviewBy;
    private LocalDateTime reviewAt;
    private String reviewReason;
    private String reviewPreviewSha;
    private String reviewRpSha;
    @TableField("native_verifications")
    private String nativeVerificationJson;
    private String deliveryRequestKey;
    private Long deliveryBy;
    private LocalDateTime deliveryAt;
    private String deliveryPreviewSha;
    private String deliveryRpSha;
    private Boolean deliveryNativeVerified;
    private String deliveryVerificationId;
}
