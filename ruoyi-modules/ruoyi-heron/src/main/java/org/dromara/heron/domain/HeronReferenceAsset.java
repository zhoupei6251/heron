package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/** 参考视频素材：授权状态、下载租约与 OSS 指针；视频字节不落业务表。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_reference_asset")
public class HeronReferenceAsset extends BaseEntity {

    public static final String STATUS_DISCOVERED = "DISCOVERED";
    public static final String STATUS_CHECKING = "CHECKING";
    public static final String STATUS_RESTRICTED = "RESTRICTED";
    public static final String STATUS_PENDING_AUTH = "PENDING_AUTH";
    public static final String STATUS_ACQUIRING = "ACQUIRING";
    public static final String STATUS_ACQUIRED = "ACQUIRED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String LICENSE_UNKNOWN = "UNKNOWN";
    public static final String LICENSE_ALLOWED = "ALLOWED";
    public static final String LICENSE_RESTRICTED = "RESTRICTED";

    @TableId private Long id;
    private Long trendId;
    private String platform;
    private String awemeId;
    private String status;
    private String licenseStatus;
    private String authorizationReason;
    private Long authorizedBy;
    private LocalDateTime authorizedAt;
    private String ossConfigKey;
    private String ossKey;
    private String ossUrl;
    private Long fileSize;
    private String contentSha256;
    private String mediaType;
    private LocalDateTime acquiredAt;
    private Integer attemptCount;
    private String runToken;
    private LocalDateTime leaseUntil;
    private Long reservedBytes;
    private String failureCode;
    private String failureReason;
}
