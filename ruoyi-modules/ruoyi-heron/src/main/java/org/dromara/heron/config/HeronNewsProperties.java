package org.dromara.heron.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 新闻来源采集边界：白名单为空时不开放任何网络来源。 */
@Data
@ConfigurationProperties(prefix = "heron.news")
public class HeronNewsProperties {

    /** 部署侧精确主机名，不接受通配符与客户端 IP。 */
    private List<String> allowedHosts = new ArrayList<>();

    /** 定时采集开关，来源审核与安全验证通过后再开启。 */
    private boolean collectionEnabled;
}
