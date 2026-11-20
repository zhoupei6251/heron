package org.dromara.heron.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 批量启用 NewsNow 平台。 */
@Data
public class NewsNowEnableBo implements Serializable {

    @NotEmpty
    @Size(max = 100)
    private List<String> platformIds;
}
