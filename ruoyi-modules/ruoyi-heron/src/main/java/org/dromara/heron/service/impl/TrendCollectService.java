package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronTrendHotword;
import org.dromara.heron.domain.HeronTrendVideo;
import org.dromara.heron.mapper.HeronTrendHotwordMapper;
import org.dromara.heron.mapper.HeronTrendVideoMapper;
import org.dromara.heron.service.IReferenceAssetService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抖音热点采集：调采集脚本，把热榜候选按位次写库。
 *
 * <p>两件事刻意不做：
 * <ol>
 *   <li>不自己算热度分——热榜顺序就是平台算过的序，第一版照抄；</li>
 *   <li>不存播放量——抖音网页端恒为 0，存一列全 0 的数只会误导统计。</li>
 * </ol>
 *
 * <p>时长过滤在这里做而不是在脚本里：脚本只管原样抓，留多少由配置决定，
 * 改口径不用改脚本。被过滤掉的作品同样入库，只是不打「够短」的标记，
 * 这样「今天热榜有没有值得做的」还能查得到全貌。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendCollectService {

    static final String PLATFORM = "douyin";

    private final HeronTrendVideoMapper videos;
    private final HeronTrendHotwordMapper hotwords;
    private final HeronNewsProperties news;
    private final IReferenceAssetService referenceAssets;

    /**
     * 采集一次并入库。
     *
     * @return 本次采到的作品数
     */
    public int collect() {
        JsonNode root = runScript();
        JsonNode list = root.path("videos");
        if (!list.isArray()) {
            throw new HeronBusinessException(502, "trend_output_invalid", "采集脚本没有返回 videos 数组");
        }
        LocalDateTime now = LocalDateTime.now();
        List<HeronTrendVideo> saved = new ArrayList<>();
        for (JsonNode item : list) {
            String awemeId = item.path("awemeId").asString("").strip();
            if (awemeId.isEmpty()) {
                continue;
            }
            HeronTrendVideo video = new HeronTrendVideo();
            video.setId(IdWorker.getId());
            video.setPlatform(PLATFORM);
            video.setAwemeId(awemeId);
            video.setRankNo(intNumber(item, "rankNo"));
            video.setDescription(limit(text(item, "desc"), 500));
            video.setAuthorNickname(limit(text(item.path("author"), "nickname"), 128));
            video.setAuthorUniqueId(limit(text(item.path("author"), "uniqueId"), 128));
            video.setAuthorFollowerCount(number(item.path("author"), "followerCount"));
            video.setDurationMs(number(item, "durationMs"));
            video.setDiggCount(number(item.path("stats"), "digg"));
            video.setCommentCount(number(item.path("stats"), "comment"));
            video.setShareCount(number(item.path("stats"), "share"));
            video.setCollectCount(number(item.path("stats"), "collect"));
            video.setRecommendCount(number(item.path("stats"), "recommend"));
            video.setDownloadUrl(limit(text(item, "downloadUrl"), 1024));
            video.setCoverUrl(limit(text(item, "coverUrl"), 1024));
            video.setPublishedAt(epochSeconds(number(item, "createTime")));
            video.setCollectedAt(now);
            video.setCreateTime(now);
            saved.add(upsert(video));
        }
        int hotwordCount = saveHotwords(root.path("hotWords"), now);
        referenceAssets.registerFromTrend(saved);
        long shortCount = saved.stream().filter(this::shortEnough).count();
        log.info("抖音热点采集完成：本次 {} 条作品（其中够短 {} 条）、{} 个热词",
            saved.size(), shortCount, hotwordCount);
        return saved.size();
    }

    /**
     * 热词按「同一个词」累积，不按次快照：连着重采到几次比单次位次更有信息量。
     *
     * <p>热词是选题线索，采不到不该让整次采集失败——作品那半边的结果已经拿到手了。
     */
    private int saveHotwords(JsonNode words, LocalDateTime now) {
        if (words == null || !words.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode item : words) {
            String word = text(item, "word");
            if (word == null || word.strip().isEmpty()) {
                continue;
            }
            word = word.strip();
            Integer rank = intNumber(item, "position");
            HeronTrendHotword incoming = new HeronTrendHotword();
            incoming.setId(IdWorker.getId());
            incoming.setPlatform(PLATFORM);
            incoming.setWord(limit(word, 200));
            incoming.setRankNo(rank);
            incoming.setBestRank(rank);
            incoming.setHotValue(number(item, "hotValue"));
            incoming.setViewCount(number(item, "viewCount"));
            incoming.setVideoCount(number(item, "videoCount"));
            incoming.setHitCount(1L);
            incoming.setFirstSeenAt(now);
            incoming.setLastSeenAt(now);
            incoming.setCreateTime(now);
            upsertHotword(incoming);
            count++;
        }
        return count;
    }

    /** 同一个词重复采到：热度与位次刷新，首次见到的时间与入库时间不动，次数 +1。 */
    private void upsertHotword(HeronTrendHotword incoming) {
        HeronTrendHotword existing = hotwords.selectOne(new LambdaQueryWrapper<HeronTrendHotword>()
            .eq(HeronTrendHotword::getPlatform, incoming.getPlatform())
            .eq(HeronTrendHotword::getWord, incoming.getWord()));
        if (existing == null) {
            hotwords.insert(incoming);
            return;
        }
        mergeHotword(incoming, existing);
        hotwords.updateById(incoming);
    }

    /**
     * 把已入库的累积值并进本次采到的行。纯函数，便于单测锁住「位次只降不升」。
     *
     * @param incoming 本次采到的新行，会被就地改写
     * @param existing 库里已有的行
     */
    static void mergeHotword(HeronTrendHotword incoming, HeronTrendHotword existing) {
        incoming.setId(existing.getId());
        incoming.setFirstSeenAt(existing.getFirstSeenAt());
        incoming.setCreateTime(existing.getCreateTime());
        incoming.setHitCount(existing.getHitCount() == null ? 2L : existing.getHitCount() + 1);
        // 位次只降不升：记得住它最好到过第几名，不会因为这轮掉下去就抹掉。
        Integer best = existing.getBestRank();
        Integer current = incoming.getBestRank();
        incoming.setBestRank(best == null || (current != null && current < best) ? current : best);
    }

    /** 同一支作品重复采到只更新热度与位次，不新增行：热榜每天都会重复出现同一批视频。 */
    private HeronTrendVideo upsert(HeronTrendVideo incoming) {
        LambdaQueryWrapper<HeronTrendVideo> query = new LambdaQueryWrapper<HeronTrendVideo>()
            .eq(HeronTrendVideo::getPlatform, incoming.getPlatform())
            .eq(HeronTrendVideo::getAwemeId, incoming.getAwemeId());
        HeronTrendVideo existing = videos.selectOne(query);
        if (existing == null) {
            videos.insert(incoming);
            return incoming;
        }
        // 首次入库时间不动：它记的是「这支视频第一次被我们看到」。
        incoming.setId(existing.getId());
        incoming.setCreateTime(existing.getCreateTime());
        videos.updateById(incoming);
        return incoming;
    }

    /** 够不够短；上限配 0 表示不过滤。时长未知时按「够短」处理，交给下游自己判。 */
    public boolean shortEnough(HeronTrendVideo video) {
        long max = news.getTrendMaxDurationMs();
        if (max <= 0) {
            return true;
        }
        return video.getDurationMs() == null || video.getDurationMs() <= max;
    }

    /**
     * 跑采集脚本。
     *
     * <p>输出落临时文件再读：脚本会打进度到 stderr，边跑边读 stdout 会在管道满时阻塞。
     */
    private JsonNode runScript() {
        // 脚本走绝对路径：子进程的工作目录不是仓库根，相对路径会找不到。
        List<String> command = List.of(news.getTrendPython(),
            Path.of(news.getTrendScript()).toAbsolutePath().toString(),
            "--scrolls", String.valueOf(news.getTrendScrolls()));
        Path out = null;
        Process process = null;
        try {
            out = Files.createTempFile("heron-trend-", ".json");
            process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .redirectOutput(out.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(news.getTrendTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new HeronBusinessException(504, "trend_timeout",
                    "抖音热点采集超时（" + news.getTrendTimeoutSeconds() + " 秒）");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new HeronBusinessException(502, "trend_failed",
                    "抖音热点采集脚本失败（退出码 " + exit + "）：页面可能被风控或结构已变");
            }
            return JsonUtils.getJsonMapper().readTree(Files.readString(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_unavailable", "采集脚本无法启动：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "trend_interrupted", "采集被中断");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException e) {
                    log.warn("采集输出临时文件删除失败 {}", out, e);
                }
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString("");
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || !value.isNumber() ? null : value.asLong();
    }

    private static Integer intNumber(JsonNode node, String field) {
        Long value = number(node, field);
        return value == null ? null : value.intValue();
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDateTime epochSeconds(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault());
    }
}
