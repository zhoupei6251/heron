package org.dromara.heron.domain.bo;

/**
 * 可定位失败信息；外部结果不明必须显式标记。
 */
public record ProductionFailureBo(String code, String stage, String inputLocator,
                                  String reason, boolean externalOutcomeUnknown) {
}
