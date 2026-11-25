package org.dromara.heron.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** 素材列表查询条件。 */
@Data
public class MediaAssetQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String licenseCode;
    private String assetKind;
    private Map<String, Object> params = new HashMap<>();
}
