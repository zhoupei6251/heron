package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.shared.production.BeatAnalysis;
import org.dromara.heron.service.impl.shared.production.ProductionArtifactService;
import org.dromara.heron.service.impl.shared.production.ProductionProcessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节拍分析：合成固定节拍音频（每 0.5s 一次打击），检测出的节拍必须落在真实节拍附近。
 * 生成无峰值的白噪声验证失败路径不静默。
 */
@DisplayName("BeatAnalysis 能量包络节拍检测")
class BeatAnalysisTest {

    @TempDir
    Path dataRoot;

    private ProductionRunVo run() {
        // runToken 必须是 UUID：prepareWorkDirectory 按它建真实工作目录
        return new ProductionRunVo(9001_0001_0002_0003L, ProductionBizType.news_explainer,
            9001_0001_0002_0004L, 9001_0001_0002_0005L, 1,
            UUID.randomUUID().toString(), "{}");
    }

    private ProductionProcessService processes() {
        var properties = new org.dromara.heron.config.HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        // Windows 开发机没有 Linux 监护脚本：与部署侧开关同口径，测试走直通路径
        properties.setGuardEnabled(false);
        return new ProductionProcessService(properties, new ProductionArtifactService(properties, null));
    }

    @Test
    @DisplayName("合成 120BPM 节拍被正确检出，允许滤波群延迟偏移 ≤80ms")
    void detectsSyntheticBeats() throws Exception {
        var run = run();
        var properties = new org.dromara.heron.config.HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        var work = new ProductionArtifactService(properties, null).prepareWorkDirectory(run);
        produceBeats(work.resolve("beat.wav"), 0.5, 8);
        var analysis = new BeatAnalysis(processes()).analyze(run, work, work.resolve("beat.wav"));
        assertEquals(0.5, analysis.tempo(), 0.05, "平均节拍间隔应约 0.5s");
        assertTrue(analysis.times().size() >= 5, "8 次打击至少检出 5 次：" + analysis.times());
        // 滤波群延迟会让检出时刻整体前移：只要逐拍间隔稳定落在 0.5s 网格即可
        for (int i = 1; i < analysis.times().size(); i++) {
            double gap = analysis.times().get(i) - analysis.times().get(i - 1);
            assertTrue(Math.abs(gap - 0.5) < 0.08, "相邻节拍间隔 " + gap + " 应约 0.5s");
        }
    }

    @Test
    @DisplayName("无节拍的平坦噪声被拒绝，不静默返回空节拍")
    void rejectsFlatNoise() throws Exception {
        var run = run();
        var properties = new org.dromara.heron.config.HeronProductionProperties();
        properties.setDataRoot(dataRoot);
        var work = new ProductionArtifactService(properties, null).prepareWorkDirectory(run);
        var process = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi",
            "-i", "anoisesrc=c=pink:r=44100:a=0.1:d=8", "-ac", "1", "-ar", "44100",
            work.resolve("flat.wav").toAbsolutePath().toString(), "-y").inheritIO().start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertThrows(HeronBusinessException.class,
            () -> new BeatAnalysis(processes()).analyze(run, work, work.resolve("flat.wav")));
    }

    /** 合成打击乐：纯 aevalsrc 表达式——底鼓脉冲直接算在一个流里，不做 amix。 */
    private static void produceBeats(Path target, double interval, int hits) throws Exception {
        StringBuilder expr = new StringBuilder("0.01*sin(2*PI*50*t)");
        for (int i = 0; i < hits; i++) {
            double at = i * interval;
            expr.append(String.format("+0.8*exp(-60*abs(t-%f))*sin(2*PI*70*t)", at));
        }
        var process = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi",
            "-i", "aevalsrc=" + expr + ":s=44100:d=" + (hits * interval + 1),
            "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le",
            target.toAbsolutePath().toString(), "-y").inheritIO().start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "合成音频生成超时");
        assertEquals(0, process.exitValue(), "合成音频必须成功");
    }
}
