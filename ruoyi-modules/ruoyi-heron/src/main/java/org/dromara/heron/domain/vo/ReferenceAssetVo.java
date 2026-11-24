package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.HeronReferenceAsset;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 参考素材展示对象。 */
@Data
@AutoMapper(target = HeronReferenceAsset.class)
public class ReferenceAssetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
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
    private String failureCode;
    private String failureReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
