package org.dromara.heron.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/** 参考素材查询条件。 */
@Data
public class ReferenceAssetQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String platform;
    private String awemeId;
    private String status;
    private String licenseStatus;
    private Map<String, Object> params;
}
