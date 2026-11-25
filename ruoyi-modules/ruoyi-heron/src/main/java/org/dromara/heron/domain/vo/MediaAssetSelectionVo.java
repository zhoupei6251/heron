package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 场景选材结果。imageValue 选中时为 OSS 源地址，V2 落盘后替换为模板相对文件名；
 * TEXT_ONLY 时固定为空串，既有模板据此关闭图片区。
 */
@Data
public class MediaAssetSelectionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String sceneId;
    private Integer sceneNumber;
    private String sceneType;
    private String status;
    private String reasonCode;
    private String reason;
    private String imageVariable;
    private String imageValue;
    private Long assetId;
    private String assetName;
    private String licenseCode;
    private String licenseEvidenceUri;
    private String attribution;
    private String sourceUri;
    private String contentSha256;
    private Integer width;
    private Integer height;
    private String ossConfigKey;
    private String ossKey;
    private MediaAssetGateVo gate;
}
