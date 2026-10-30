package org.dromara.heron.domain.bo;

import lombok.Data;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** 项目查询；归档默认隐藏，owner 范围只能由服务端计算。 */
@Data
public class ProtoProjectQuery implements Serializable {
    private String name;
    private String archived = "0";
    private Map<String, Object> params = new HashMap<>();
}
