package org.dromara.heron.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/** 视频画面素材：来源、显式许可、图像尺寸、内容指纹与 OSS 指针。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("heron_media_asset")
public class MediaAsset extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String KIND_IMAGE = "IMAGE";
    public static final String DELETED = "1";
    public static final String ACTIVE = "0";

    @TableId private Long id;
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
    @TableLogic private String delFlag;
}
