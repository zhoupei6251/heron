package org.dromara.heron.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 新闻来源采集边界：白名单为空时不开放任何网络来源。 */
@Data
@ConfigurationProperties(prefix = "heron.news")
public class HeronNewsProperties {

    /** 部署侧精确主机名，不接受通配符与客户端 IP。 */
    private List<String> allowedHosts = new ArrayList<>();

    /** NewsNow 热榜实例基址；默认公共实例，切自托管（如 news.heronaigc.top）只改这一行。 */
    private String newsnowBaseUrl = "https://newsnow.busiyi.world";

    /** NewsNow 条目是否允许进入解说线正文抓取；默认关闭，避免原站反爬把失败率推高。 */
    private boolean newsnowArticleBodyAllowed;

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

    /** 发布出口：sandbox 只跑通链路；douyin-cli 调 social-auto-upload 的 sau 命令；youtube 走 Data API v3。默认沙箱，换出口必须显式配置。 */
    private String publishAdapter = "sandbox";

    /** YouTube 独立开关；默认关闭，打开仍需补齐 OAuth 凭据。 */
    private boolean youtubeEnabled;

    /** Google OAuth 客户端 ID；由部署环境注入，不写进仓库。 */
    @ToString.Exclude
    private String youtubeClientId;

    /** Google OAuth 客户端密钥；禁止日志输出。 */
    @ToString.Exclude
    private String youtubeClientSecret;

    /** 已取得 offline access 的 OAuth refresh token；禁止日志输出。 */
    @ToString.Exclude
    private String youtubeRefreshToken;

    /** YouTube 新视频可见性；默认 private，显式开启出口也不默认公开。 */
    private String youtubePrivacyStatus = "private";

    /** 冻结在发布尝试里的账号标识；实际目标频道由 OAuth refresh token 授权决定。 */
    private String youtubeAccount = "youtube";

    /** sau 可执行文件；用 social-auto-upload 的虚拟环境安装后指向其中的 sau.exe。 */
    private String publishCommand = "sau";

    /** sau 登录时用的抖音账号名，一个名字对应一份 cookie。 */
    private String publishAccount = "heron";

    /** 浏览器自动化发布耗时几十秒，超时判失败并提示去后台确认。 */
    private int publishTimeoutSeconds = 180;

    /**
     * 数据机会分析开关。第一步只算机会、不接入生产，默认关闭；
     * 关闭时既没有定时任务，也不影响任何现有链路，只能由接口手动触发。
     */
    private boolean dataAnalysisEnabled;

    /** 数据机会分析的 cron：跑在第一个产出槽位之前，产出决策才有机会看到当天结果。 */
    private List<String> dataAnalysisSlots = new ArrayList<>();

    /** 分析窗口点数；与源的 cadence 一起解释，同一个数字对日频与年频含义不同。 */
    private int dataAnalysisWindowPoints = 120;

    /**
     * 知识包来源：每个源一个目录 {@code data/knowledge/<source-key>}。
     *
     * <p>{@code auto} 是逐源自动准入开关，默认关；人工触发不受它限制。
     * 不在清单里的 sourceKey 即使库里有候选也不进池——知识线同样要能逐源下架。
     */
    private List<KnowledgeSource> knowledgeSources = new ArrayList<>();

    /** 字幕烧录会增加一次整片编码，默认关以保持既有成片逐字节不变。 */
    private Subtitles subtitles = new Subtitles();

    /** 封面抽帧与裁切默认关，待字幕烧录稳定观察后再逐期启用。 */
    private Cover cover = new Cover();

    @Data
    public static class Subtitles {
        private boolean burn;
    }

    @Data
    public static class Cover {
        private boolean enabled;
    }

    @Data
    public static class KnowledgeSource {
        private String sourceKey;
        private boolean auto;
    }

    /**
     * 市面爆款采集：定时跑抖音热点榜单，把热视频按位次存下来。
     *
     * <p>默认关。纯 HTTP 取不到数据（接口回 200 但正文为空），只能靠浏览器渲染后拦截
     * 页面自己发的请求，所以这里要配解释器与脚本，不是一条 HTTP 调用。
     */
    private boolean trendEnabled;

    /** 采集 cron；空清单表示只由接口手动触发。 */
    private List<String> trendSlots = new ArrayList<>();

    /** 采集脚本路径，相对仓库根。 */
    private String trendScript = "data/tools/douyin-trend/trend.py";

    /** 跑采集脚本的 Python；用 social-auto-upload 的虚拟环境（其中已装 patchright）。 */
    private String trendPython = "data/tools/social-auto-upload/.venv/Scripts/python.exe";

    /** 采集脚本超时；抓不到数据时宁可失败也不要挂着。 */
    private int trendTimeoutSeconds = 180;

    /** 页面滚动次数：每滚一次多拿一批热视频，次数越多越慢。 */
    private int trendScrolls = 6;

    /**
     * 只留不超过这么久（毫秒）的作品。热榜里混着几分钟的长片，
     * 拆解一条要好几分钟，第一版先只处理短视频。0 表示不过滤。
     */
    private long trendMaxDurationMs = 90_000L;

    /** 学习批次最多选几条；硬上限 10，允许模型一条不选。 */
    private int trendLearnBatchSize = 10;

    /** 学习原片独立体积上限，不复用参考素材与新闻正文的限制。 */
    private long trendLearnMaxBytes = 100L * 1024 * 1024;

    /** rea-video 可执行文件或脚本路径。 */
    private String trendLearnCommand = "rea-video";

    /** 单条视频拆解超时。 */
    private int trendLearnTimeoutSeconds = 600;

    /** 技法卡与横向汇总的本地目录。 */
    private Path trendLearnOutputDir = Path.of("docs/learned");

    /** 单条参考视频上限；超限在读取前或流式写入时拒绝。 */
    private long referenceMaxBytes = 50L * 1024 * 1024;

    /** 参考素材总容量上限；ACQUIRING 按单文件上限预留，避免并发突破。 */
    private long referenceMaxTotalBytes = 512L * 1024 * 1024;

    /** 单实例并发下载数；数据库租约仍然阻止同一素材重复领取。 */
    private int referenceMaxConcurrent = 2;

    private int referenceConnectTimeoutSeconds = 10;

    private int referenceReadTimeoutSeconds = 120;

    private int referenceMaxRedirects = 3;

    /** 下载租约时长；超时后其他 worker 可以接管。 */
    private int referenceLeaseSeconds = 180;
}
