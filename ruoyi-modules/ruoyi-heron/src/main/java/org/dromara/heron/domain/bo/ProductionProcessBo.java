package org.dromara.heron.domain.bo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 受控子进程请求；路径与工具名都需服务端校验。
 */
public record ProductionProcessBo(String tool, List<String> arguments,
                                  Path workDirectory, Duration timeout,
                                  Map<String, String> environment) {

    public ProductionProcessBo {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public ProductionProcessBo(String tool, List<String> arguments, Path workDirectory, Duration timeout) {
        this(tool, arguments, workDirectory, timeout, Map.of());
    }
}
