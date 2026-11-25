package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 调用 rea-video 的受控拆解边界：独立目录、超时、输出上限与必需产物校验。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendVideoDecomposer {

    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int STOP_GRACE_SECONDS = 5;

    private final HeronNewsProperties properties;

    public Decomposition decompose(Path video, Path workDir) {
        if (video == null || !Files.isRegularFile(video)) {
            throw new HeronBusinessException(502, "trend_decompose_video_missing", "待拆解视频不存在");
        }
        int timeout = properties.getTrendLearnTimeoutSeconds();
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS) {
            throw new HeronBusinessException(503, "trend_decompose_config_invalid", "拆解超时必须位于 1 到 600 秒");
        }
        String command = properties.getTrendLearnCommand();
        if (command == null || command.isBlank()) {
            throw new HeronBusinessException(503, "trend_decompose_not_configured", "尚未配置 rea-video 命令");
        }
        Path output = workDir.resolve("out");
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_decompose_workdir_failed", "拆解输出目录无法创建", e);
        }
        List<String> argv = List.of(command, "decompose", video.toString(), "-o", output.toString());
        Process process;
        try {
            process = start(argv, workDir);
        } catch (IOException e) {
            throw new HeronBusinessException(503, "trend_decompose_unavailable", "rea-video 无法启动", e);
        }
        BoundedOutput stdout = new BoundedOutput();
        BoundedOutput stderr = new BoundedOutput();
        Thread outReader = drain(process.getInputStream(), stdout);
        Thread errReader = drain(process.getErrorStream(), stderr);
        boolean finished = false;
        boolean interrupted = false;
        try (var stdin = process.getOutputStream()) {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            finished = false;
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            finished = false;
        } finally {
            if (!finished) {
                terminate(process);
            }
            join(outReader);
            join(errReader);
        }
        if (stdout.overflow || stderr.overflow) {
            throw new HeronBusinessException(502, "trend_decompose_output_limit", "rea-video 输出超过 1MiB");
        }
        if (interrupted) {
            throw new HeronBusinessException(503, "trend_decompose_interrupted", "视频拆解被中断");
        }
        if (!finished) {
            throw new HeronBusinessException(504, "trend_decompose_timeout", "视频拆解超时（" + timeout + " 秒）");
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new HeronBusinessException(502, "trend_decompose_failed",
                "rea-video 拆解失败（退出码 " + exit + "）：" + tail(stderr.text(), 800));
        }
        for (String name : TrendLearnStorage.RAW_FILES) {
            if (!Files.isRegularFile(output.resolve(name))) {
                throw new HeronBusinessException(502, "trend_decompose_output_missing", "rea-video 缺少产物 " + name);
            }
        }
        return new Decomposition(workDir, output);
    }

    Process start(List<String> argv, Path workDir) throws IOException {
        return new ProcessBuilder(argv).directory(workDir.toFile()).start();
    }

    private static Thread drain(InputStream input, BoundedOutput output) {
        Thread thread = new Thread(() -> output.consume(input), "heron-trend-decompose-" + output.hashCode());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(STOP_GRACE_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(String text, int max) {
        if (text == null || text.isBlank()) {
            return "无错误输出";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(flat.length() - max);
    }

    private static final class BoundedOutput {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean overflow;

        private void consume(InputStream input) {
            byte[] chunk = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    int remaining = MAX_OUTPUT_BYTES - buffer.size();
                    if (read > remaining) {
                        if (remaining > 0) {
                            buffer.write(chunk, 0, remaining);
                        }
                        overflow = true;
                        return;
                    }
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // 进程被终止时读取端关闭
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    public record Decomposition(Path workDir, Path outputDir) implements AutoCloseable {
        @Override
        public void close() {
            try (Stream<Path> paths = Files.walk(workDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                log.warn("拆解临时目录清理失败 {}", workDir, e);
            }
        }
    }
}
