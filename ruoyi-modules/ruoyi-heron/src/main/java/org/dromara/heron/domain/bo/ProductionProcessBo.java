package org.dromara.heron.domain.bo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 受控子进程请求；路径与工具名都需服务端校验。
 */
public record ProductionProcessBo(String tool, List<String> arguments,
                                  Path workDirectory, Duration timeout) {
}
