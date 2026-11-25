package org.dromara.heron.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.heron.domain.MediaAsset;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 素材展示对象。 */
@Data
@AutoMapper(target = MediaAsset.class)
public class MediaAssetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String assetKind;
    private String sourceUri;
    private String licenseCode;
    private String licenseEvidenceUri;
    private String attribution;
    private String tags;
    private Integer width;
    private Integer height;
    private String orientation;
    private String contentSha256;
    private Long sizeBytes;
    private String mediaType;
    private String ossConfigKey;
    private String ossKey;
    private String ossUrl;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
