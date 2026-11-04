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

    private String hyperframes;

    /** Windows 开发机上 Linux 监护脚本跑不起来，只能显式关掉；关掉就没有 rlimit 三项，不做平台自动探测。 */
    private boolean guardEnabled = true;

    private Path prototypeTemplate;

    private Path newsTemplate;

    /** 指向基座 sys_oss_config 的 config_key；留空即本地产物盘模式，测试与未接对象存储的部署行为不变。 */
    private String ossConfigKey;
}
