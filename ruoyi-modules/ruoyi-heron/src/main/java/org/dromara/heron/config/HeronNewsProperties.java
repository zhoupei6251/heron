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

    /** 自动产出总开关；关闭时槽位触发只留痕，不认领不生产。 */
    private boolean autoEnabled;

    /** Spring cron 列表：每条触发一次「最多产出一条」，条数即每天产出上限。 */
    private List<String> autoSlots = new ArrayList<>();

    /** 自动槽位固定使用的模板；为空才交给模板选择器。配置了但模板不存在时自动产出直接失败。 */
    private String autoTemplate;

    /** 自动模式认领的稿件由扫描自动确认发布；关闭则停在待确认，等人处理。 */
    private boolean autoConfirmEnabled = true;

    /** 自动模式写库时代表系统的操作人；默认 admin（userId=1）。 */
    private Long autoActorId = 1L;

    /** 发布出口：sandbox 只跑通链路；douyin-cli 调 social-auto-upload 的 sau 命令。默认沙箱，换出口必须显式配置。 */
    private String publishAdapter = "sandbox";

    /** sau 可执行文件；用 social-auto-upload 的虚拟环境安装后指向其中的 sau.exe。 */
    private String publishCommand = "sau";

    /** sau 登录时用的抖音账号名，一个名字对应一份 cookie。 */
    private String publishAccount = "heron";

    /** 浏览器自动化发布耗时几十秒，超时判失败并提示去后台确认。 */
    private int publishTimeoutSeconds = 180;
}
