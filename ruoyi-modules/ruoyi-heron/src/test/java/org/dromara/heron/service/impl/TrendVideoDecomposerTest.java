package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.research.TrendLearnStorage;
import org.dromara.heron.service.impl.shared.research.TrendVideoDecomposer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendVideoDecomposerTest {

    @TempDir
    Path tempDir;

    @Test
    void successfulRunRequiresAllArtifactsAndCleansWorkDirectory() throws Exception {
        Path video = Files.writeString(tempDir.resolve("source.mp4"), "video");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        TrendVideoDecomposer decomposer = new FakeDecomposer(0, false, "");

        try (TrendVideoDecomposer.Decomposition result = decomposer.decompose(video, work)) {
            for (String name : TrendLearnStorage.RAW_FILES) {
                assertTrue(Files.isRegularFile(result.outputDir().resolve(name)));
            }
        }
        assertFalse(Files.exists(work));
    }

    @Test
    void nonzeroExitReportsStderr() throws Exception {
        Path video = Files.writeString(tempDir.resolve("source.mp4"), "video");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        TrendVideoDecomposer decomposer = new FakeDecomposer(7, false, "decode failed");

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> decomposer.decompose(video, work));

        assertEquals("trend_decompose_failed", error.getCode());
        assertTrue(error.getMessage().contains("decode failed"));
    }

    @Test
    void timeoutIsExplicit() throws Exception {
        Path video = Files.writeString(tempDir.resolve("source.mp4"), "video");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        TrendVideoDecomposer decomposer = new FakeDecomposer(0, true, "");

        HeronBusinessException error = assertThrows(HeronBusinessException.class,
            () -> decomposer.decompose(video, work));

        assertEquals("trend_decompose_timeout", error.getCode());
    }

    private final class FakeDecomposer extends TrendVideoDecomposer {
        private final int exit;
        private final boolean timeout;
        private final String stderr;

        private FakeDecomposer(int exit, boolean timeout, String stderr) {
            super(properties());
            this.exit = exit;
            this.timeout = timeout;
            this.stderr = stderr;
        }

        @Override
        protected Process start(List<String> argv, Path workDir) {
            return new FakeProcess(argv, workDir, exit, timeout, stderr);
        }
    }

    private static HeronNewsProperties properties() {
        HeronNewsProperties properties = new HeronNewsProperties();
        properties.setTrendLearnCommand("rea-video");
        properties.setTrendLearnTimeoutSeconds(2);
        return properties;
    }

    private static final class FakeProcess extends Process {
        private final List<String> argv;
        private final Path workDir;
        private final int exit;
        private final boolean timeout;
        private final ByteArrayInputStream stdout;
        private final ByteArrayInputStream stderr;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private boolean alive = true;

        private FakeProcess(List<String> argv, Path workDir, int exit, boolean timeout, String stderr) {
            this.argv = argv;
            this.workDir = workDir;
            this.exit = exit;
            this.timeout = timeout;
            this.stdout = new ByteArrayInputStream(new byte[0]);
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean waitFor(long timeoutValue, TimeUnit unit) throws InterruptedException {
            if (timeout) {
                return false;
            }
            try {
                Path output = Path.of(argv.get(argv.size() - 1));
                Files.createDirectories(output);
                for (String name : TrendLearnStorage.RAW_FILES) {
                    Files.writeString(output.resolve(name), name);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exit;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public ProcessHandle.Info info() {
            return null;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public long pid() {
            return 1L;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int waitFor() {
            return exit;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public java.io.InputStream getInputStream() {
            return stdout;
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return stderr;
        }
    }
}
