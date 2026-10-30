package org.dromara.heron.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 生产执行的文件与工具边界，默认不启动后台执行。
 */
@Data
@ConfigurationProperties(prefix = "heron.production")
public class HeronProductionProperties {

    private Path dataRoot = Path.of("data");

    private long newsMaxBytes = 200L * 1024 * 1024;

    private long prototypeMaxBytes = 50L * 1024 * 1024;

    private boolean workerEnabled;

    private int cleanupRetentionDays = 7;

    private String python = "python3";

    private String node = "node";

    private String ffmpeg = "ffmpeg";

    private String ffprobe = "ffprobe";

    private String zeeRp = "zee-rp";

    private Path prototypeTemplate;
}
