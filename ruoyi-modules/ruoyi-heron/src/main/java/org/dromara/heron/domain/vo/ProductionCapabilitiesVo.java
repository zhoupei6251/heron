package org.dromara.heron.domain.vo;

import java.util.List;

/**
 * 两条固定业务线的能力状态，内容脱敏。
 */
public record ProductionCapabilitiesVo(Capability news, Capability prototype) {

    /** targetAxureVersion 只给原型线：人工验证要逐字比对部署配置的目标 Axure 版本，不能让人猜字符串。 */
    public record Capability(boolean configured, boolean available,
                             List<String> reasons, int capacity, String targetAxureVersion) {
    }
}
