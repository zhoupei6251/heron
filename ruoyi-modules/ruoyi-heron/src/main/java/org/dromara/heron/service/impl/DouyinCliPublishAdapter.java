package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.PublishReceipt;
import org.dromara.heron.domain.PublishRequest;
import org.dromara.heron.service.IPublishAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抖音发布出口：调 social-auto-upload 的 sau 命令（浏览器自动化），不碰开放平台凭据。
 * 该命令没有「发布后回传作品链接」的输出，回执的 workUrl 留空；界面据 source 显示「已提交，待补链接」。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "heron.news", name = "publish-adapter", havingValue = DouyinCliPublishAdapter.SOURCE)
public class DouyinCliPublishAdapter implements IPublishAdapter {
    public static final String SOURCE = "douyin-cli";
    private static final int OUTPUT_LIMIT = 1200;

    private final HeronNewsProperties properties;

    public DouyinCliPublishAdapter(HeronNewsProperties properties) {
        this.properties = properties;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public PublishReceipt publish(PublishRequest request) {
        Path file = request.videoFile();
        if (file == null || !Files.isRegularFile(file)) {
            throw new HeronBusinessException(409, "publish_file_missing", "成片没有本地文件，命令行无法上传");
        }
        String title = request.title() == null ? "" : request.title().strip();
        if (title.isEmpty()) {
            throw new HeronBusinessException(409, "publish_title_missing", "稿件没有标题，不能发布");
        }
        String account = properties.getPublishAccount() == null ? "" : properties.getPublishAccount().strip();
        if (account.isEmpty()) {
            throw new HeronBusinessException(503, "publish_account_missing", "未配置抖音账号名");
        }
        run(command(properties.getPublishCommand(), account, file, title));
        return new PublishReceipt(null, account, request.requestedAt().truncatedTo(ChronoUnit.MILLIS), SOURCE);
    }

    /** 与 skills/douyin-upload/references/cli-contract.md 的入参一一对应；参数走列表，不拼 shell。 */
    static List<String> command(String executable, String account, Path file, String title) {
        return List.of(executable, "douyin", "upload-video",
            "--account", account, "--file", file.toAbsolutePath().toString(), "--title", title);
    }

    /** 输出直接落临时文件：边跑边读会阻塞管道，跑完再读又可能撑爆缓冲区。 */
    private void run(List<String> command) {
        Path logFile = null;
        try {
            logFile = Files.createTempFile("heron-sau-", ".log");
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(properties.getPublishTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new HeronBusinessException(504, "publish_timeout",
                    "发布命令超时，请先到抖音后台确认是否已发出，不要重复提交：" + tail(logFile));
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new HeronBusinessException(502, "publish_failed", "发布命令失败（" + exit + "）：" + tail(logFile));
            }
        } catch (IOException e) {
            throw new HeronBusinessException(503, "publish_unavailable", "发布命令无法启动：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, "publish_interrupted", "发布命令被中断");
        } finally {
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException e) {
                    log.warn("发布日志删除失败 {}", logFile, e);
                }
            }
        }
    }

    private static String tail(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            return text.length() <= OUTPUT_LIMIT ? text : text.substring(text.length() - OUTPUT_LIMIT);
        } catch (IOException e) {
            return "";
        }
    }
}
