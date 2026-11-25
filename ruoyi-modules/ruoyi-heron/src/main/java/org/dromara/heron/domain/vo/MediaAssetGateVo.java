package org.dromara.heron.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** 选择结果附带的 assets 闸结论；缺素材的文字回退也是通过，不是空帧。 */
@Data
public class MediaAssetGateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String gate;
    private String inputLocator;
    private boolean passed;
    private String reason;
    private Map<String, Object> measured;

    public MediaAssetGateVo(String inputLocator, String reasonCode, String reason,
                            boolean licenseChecked, Long assetId, boolean fallbackToText) {
        this.gate = "assets";
        this.inputLocator = inputLocator;
        this.passed = true;
        this.reason = reasonCode + " " + reason;
        this.measured = new LinkedHashMap<>();
        this.measured.put("licenseChecked", licenseChecked);
        this.measured.put("fallbackToText", fallbackToText);
        this.measured.put("assetPresent", assetId != null);
        if (assetId != null) {
            this.measured.put("assetId", Long.toString(assetId));
        }
    }
}
