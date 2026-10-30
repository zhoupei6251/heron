package org.dromara.heron.domain.vo;

/**
 * 受控子进程结果；stdout/stderr 仅保留受限长度。
 */
public record ProductionProcessVo(int exitCode, boolean timedOut, boolean stopped,
                                  String stdout, String stderr) {
}
