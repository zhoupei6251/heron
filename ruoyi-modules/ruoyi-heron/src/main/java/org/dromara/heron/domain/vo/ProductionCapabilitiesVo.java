package org.dromara.heron.domain.vo;

import java.util.List;

/**
 * 两条固定业务线的能力状态，内容脱敏。
 */
public record ProductionCapabilitiesVo(Capability news, Capability musicVideo) {

    /** 只报告本地执行条件，不触发外部模型/TTS 调用。 */
    public record Capability(boolean configured, boolean available, List<String> reasons, int capacity) {
    }
}
