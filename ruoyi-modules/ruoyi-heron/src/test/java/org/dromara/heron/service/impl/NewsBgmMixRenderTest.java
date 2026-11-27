package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.production.NewsNarrationTiming;
import org.dromara.heron.service.impl.shared.production.NewsProductionPipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 仅验证真实FFmpeg包络；静音口播与恒定信号隔离增益，不调用配音或发布服务。 */
@Tag("render")
class NewsBgmMixRenderTest {
    @TempDir
    Path output;

    @Test
    void keepsBodyLowThenRisesAndFadesAtActualEnd() throws Exception {
        float[] samples = mix(new NewsNarrationTiming.Span("closing-slot", 28000, 41500, false), "normal");
        assertEquals(41.5, samples.length / 48000.0, 0.002);
        assertEquals(0.22, mean(samples, 2, 2.2), 0.002);
        assertTrue(mean(samples, 28.35, 28.45) > 0.28);
        assertTrue(mean(samples, 28.35, 28.45) < 0.33);
        assertEquals(0.40, mean(samples, 29, 29.2), 0.002);
        assertTrue(mean(samples, 41.35, 41.49) < 0.06);
    }

    @Test
    void shortClosingAlsoFinishesInsideActualAudio() throws Exception {
        float[] samples = mix(new NewsNarrationTiming.Span("closing-slot", 41000, 41500, false), "short");
        assertEquals(41.5, samples.length / 48000.0, 0.002);
        assertEquals(0.22, mean(samples, 40, 40.2), 0.002);
        assertTrue(mean(samples, 41.48, 41.5) < 0.04);
    }

    private float[] mix(NewsNarrationTiming.Span closing, String name) throws Exception {
        Path pcm = output.resolve(name + ".f32");
        Path log = output.resolve(name + ".log");
        // 这里只验包络形状：恒定信号进包络，读回的实际音量就是包络值本身
        Process process = new ProcessBuilder(List.of("ffmpeg", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono:d=41.5",
            "-f", "lavfi", "-i", "aevalsrc=1:s=48000:d=50",
            "-filter_complex", "[1:a]" + NewsProductionPipeline.bgmEnvelope(closing) + "[bg]", "-map", "[bg]",
            "-c:a", "pcm_f32le", "-f", "f32le", pcm.toString()))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("FFmpeg混音验证超时");
        }
        assertEquals(0, process.exitValue(), Files.readString(log));
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes.remaining() / Float.BYTES];
        bytes.asFloatBuffer().get(samples);
        return samples;
    }

    private static double mean(float[] samples, double start, double end) {
        int from = (int) (start * 48000);
        int to = Math.min(samples.length, (int) (end * 48000));
        double sum = 0;
        for (int index = from; index < to; index++) {
            assertTrue(Float.isFinite(samples[index]));
            sum += Math.abs(samples[index]);
        }
        return sum / (to - from);
    }
}
