package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 固定工具的受控执行；真实隔离依赖 Linux 监护脚本，其他平台只能做可移植校验。
 */
@Slf4j
@Service
public class ProductionProcessService {

    private static final Set<String> TOOLS = Set.of("node", "ffmpeg", "ffprobe", "python", "zee-rp");
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_ARGUMENT_LENGTH = 2048;
    private static final int MAX_TIMEOUT_SECONDS = 240;
    private static final int STOP_GRACE_SECONDS = 10;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronProductionProperties properties;
    private final ProductionArtifactService artifacts;
    private final ProductionRuntimeIdentityService identities;

    public ProductionProcessService(HeronProductionProperties properties, ProductionArtifactService artifacts) {
        this(properties, artifacts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionProcessService(HeronProductionProperties properties, ProductionArtifactService artifacts,
                                    ProductionRuntimeIdentityService identities) {
        this.properties = properties;
        this.artifacts = artifacts;
        this.identities = identities;
    }

    public ProductionProcessVo run(ProductionRunVo run, ProductionProcessBo command) {
        int timeoutSeconds = validate(run, command);
        Path work = artifacts.prepareWorkDirectory(run);
        Path identity = work.resolve("guard-identity.json");
        List<String> argv = new ArrayList<>(List.of(
            properties.getPython(),
            guardScript().toString(),
            "--timeout", Integer.toString(timeoutSeconds),
            "--identity", identity.toString(),
            "--",
            toolPath(command.tool())));
        argv.addAll(command.arguments() == null ? List.of() : command.arguments());

        Process process;
        try {
            process = new ProcessBuilder(argv).directory(work.toFile()).start();
        } catch (IOException e) {
            throw new HeronBusinessException(503, "production_guard_unavailable", "受控执行不可用");
        }
        if (!persistIdentity(run, identity)) {
            terminate(process);
            throw new HeronBusinessException(503, "production_identity_unavailable", "执行身份不可用");
        }
        BoundedOutput stdout = new BoundedOutput();
        BoundedOutput stderr = new BoundedOutput();
        Thread outReader = drain(process.getInputStream(), stdout);
        Thread errReader = drain(process.getErrorStream(), stderr);

        boolean interrupted = false;
        boolean timedOut = false;
        boolean stopped = false;
        try (OutputStream stdin = process.getOutputStream()) {
            if (!process.waitFor(timeoutSeconds + STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                timedOut = true;
                stopped = true;
            }
        } catch (InterruptedException e) {
            interrupted = true;
            stopped = true;
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 关闭 stdin 即触发监护脚本终止子组，无需额外处理
        } finally {
            if (stopped) {
                terminate(process);
            }
            join(outReader);
            join(errReader);
        }
        if (stdout.overflow || stderr.overflow) {
            throw new HeronBusinessException(409, "production_output_limit", "子进程输出超过 1MiB");
        }
        if (interrupted) {
            throw new HeronBusinessException(409, "production_process_interrupted", "执行被中断");
        }

        int exitCode = process.exitValue();
        Map<String, Object> result = identityResult(identity);
        if (result != null) {
            exitCode = number(result.get("exitCode"), exitCode);
            timedOut = timedOut || Boolean.TRUE.equals(result.get("timedOut"));
            stopped = stopped || Boolean.TRUE.equals(result.get("stopped"));
        }
        return new ProductionProcessVo(exitCode, timedOut, stopped,
            stdout.text(), stderr.text());
    }

    /**
     * 只按步骤里持久化的启动身份终止；新恢复令牌不参与旧目录推导。
     */
    public boolean stopAndConfirm(ProductionRunVo run) {
        String identityJson = run == null || identities == null
            ? null : identities.latest(run.taskId(), run.attemptNo());
        if (identityJson == null) {
            return false;
        }
        Map<String, Object> data;
        try {
            data = JSON.readValue(identityJson, Map.class);
        } catch (RuntimeException e) {
            return false;
        }
        long guardPid = number(data.get("guardPid"), -1);
        long pgid = number(data.get("pgid"), -1);
        long guardStartTicks = number(data.get("guardStartTicks"), -1);
        long childStartTicks = number(data.get("childStartTicks"),
            number(data.get("startTicks"), -1));
        String bootId = data.get("bootId") instanceof String value ? value : null;
        if (guardPid <= 0 || pgid <= 0 || childStartTicks < 0) {
            return false;
        }
        if (bootId != null && !bootId.isBlank()) {
            String current = currentBootId();
            if (current == null) {
                return false;
            }
            if (!bootId.equals(current)) {
                return true;
            }
        }
        if (File.separatorChar != '/') {
            return false;
        }
        boolean guardAlive = identityMatches(guardPid, guardStartTicks);
        boolean childGroupAlive = identityMatches(pgid, childStartTicks);
        if (!guardAlive && !childGroupAlive) {
            return groupGone(pgid);
        }
        if (guardAlive) {
            ProcessHandle.of(guardPid).ifPresent(ProcessHandle::destroy);
        }
        if (childGroupAlive) {
            signalGroup(pgid, "TERM");
        }
        if (awaitGone(guardPid, guardStartTicks, pgid, childStartTicks)) {
            return true;
        }
        if (identityMatches(guardPid, guardStartTicks)) {
            ProcessHandle.of(guardPid).ifPresent(ProcessHandle::destroyForcibly);
        }
        if (identityMatches(pgid, childStartTicks)) {
            signalGroup(pgid, "KILL");
        }
        return awaitGone(guardPid, guardStartTicks, pgid, childStartTicks);
    }

    private static boolean awaitGone(long guardPid, long guardStartTicks, long pgid,
                                     long childStartTicks) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_GRACE_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!identityMatches(guardPid, guardStartTicks) && groupGone(pgid)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !identityMatches(guardPid, guardStartTicks) && groupGone(pgid);
    }

    private static void signalGroup(long pgid, String signal) {
        Path kill = Files.isExecutable(Path.of("/bin/kill"))
            ? Path.of("/bin/kill") : Path.of("/usr/bin/kill");
        try {
            Process process = new ProcessBuilder(kill.toString(), "-" + signal, "--", "-" + pgid)
                .redirectErrorStream(true).start();
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean persistIdentity(ProductionRunVo run, Path identity) {
        if (identities == null) {
            return true;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(identity)) {
                try {
                    return identities.record(run, Files.readString(identity));
                } catch (IOException e) {
                    // 监护脚本尚未写完，下一轮再读
                } catch (RuntimeException e) {
                    log.warn("运行身份写入失败: taskId={}", run.taskId());
                    return false;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private int validate(ProductionRunVo run, ProductionProcessBo command) {
        if (run == null || command == null || command.tool() == null || !TOOLS.contains(command.tool())) {
            throw new HeronBusinessException(400, "production_invalid_tool", "工具不在允许清单内");
        }
        List<String> arguments = command.arguments() == null ? List.of() : command.arguments();
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new HeronBusinessException(400, "production_invalid_arguments", "参数数量超限");
        }
        for (String argument : arguments) {
            if (argument == null || argument.length() > MAX_ARGUMENT_LENGTH
                || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new HeronBusinessException(400, "production_invalid_arguments", "参数内容不合法");
            }
        }
        int timeout = command.timeout() == null ? MAX_TIMEOUT_SECONDS : (int) command.timeout().toSeconds();
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS) {
            throw new HeronBusinessException(400, "production_invalid_timeout", "超时不在允许范围");
        }
        if (command.workDirectory() != null) {
            Path expected = artifacts.prepareWorkDirectory(run).toAbsolutePath().normalize();
            if (!expected.equals(command.workDirectory().toAbsolutePath().normalize())) {
                throw new HeronBusinessException(400, "production_invalid_workdir", "工作目录必须来自本次尝试");
            }
        }
        return timeout;
    }

    private String toolPath(String tool) {
        return switch (tool) {
            case "node" -> properties.getNode();
            case "ffmpeg" -> properties.getFfmpeg();
            case "ffprobe" -> properties.getFfprobe();
            case "python" -> properties.getPython();
            default -> properties.getZeeRp();
        };
    }

    private Path guardScript() {
        Path directory = properties.getDataRoot().toAbsolutePath().normalize().resolve("runtime");
        Path script = directory.resolve("process_guard.py");
        try (InputStream resource = getClass().getResourceAsStream("/heron/process_guard.py")) {
            if (resource == null) {
                throw new IllegalStateException("缺少 process_guard.py");
            }
            Files.createDirectories(directory);
            Path temporary = directory.resolve("process_guard.py." + java.util.UUID.randomUUID() + ".tmp");
            Files.copy(resource, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, script, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            return script;
        } catch (IOException e) {
            throw new HeronBusinessException(503, "production_guard_unavailable", "监护脚本不可用");
        }
    }

    private static Thread drain(InputStream input, BoundedOutput output) {
        Thread thread = new Thread(() -> output.consume(input), "heron-process-" + output.hashCode());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static boolean identityMatches(long pid, long startTicks) {
        if (startTicks < 0) {
            return false;
        }
        try {
            String data = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            String[] fields = data.substring(data.lastIndexOf(')') + 2).split(" ");
            return !"Z".equals(fields[0]) && Long.parseLong(fields[19]) == startTicks;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean groupGone(long pgid) {
        Path proc = Path.of("/proc");
        if (!Files.isDirectory(proc)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(proc)) {
            return entries
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().chars().allMatch(Character::isDigit))
                .noneMatch(path -> groupOf(path) == pgid);
        } catch (IOException e) {
            return false;
        }
    }

    private static long groupOf(Path processDirectory) {
        try {
            String data = Files.readString(processDirectory.resolve("stat"));
            String[] fields = data.substring(data.lastIndexOf(')') + 2).split(" ");
            if ("Z".equals(fields[0])) {
                return -1;
            }
            return Long.parseLong(fields[2]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static String currentBootId() {
        try {
            return Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> identityResult(Path identity) {
        try {
            Map<String, Object> data = JSON.readValue(Files.readString(identity), Map.class);
            Object result = data.get("result");
            return result instanceof Map<?, ?> ? castMap(result) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
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
                // 子进程被终止时读取端关闭
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
