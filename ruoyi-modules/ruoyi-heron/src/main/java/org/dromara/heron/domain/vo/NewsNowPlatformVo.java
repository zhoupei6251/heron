package org.dromara.heron.domain.vo;

import lombok.Data;

/** NewsNow 平台目录项：地址由部署基址拼出，enabled 表示同平台来源已存在。 */
@Data
public class NewsNowPlatformVo {

    private String platformId;

    private String name;

    private String url;

    private boolean enabled;
}
