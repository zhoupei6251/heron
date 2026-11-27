package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BGM 节拍分析：能量包络峰值检测，产出节拍时刻表给视觉同步。
 *
 * <p>为什么不引 librosa/aubio：整库依赖只为一个 onset 检测太重；低频段
 * RMS 包络 + 滑动均值阈值在这个仓库的 BGM（电子/氛围类）上足够稳定，
 * Java 端零新依赖。误差可接受：视觉同步要的是节拍感，不是乐谱级精度。
 */
@Service
@RequiredArgsConstructor
public class BeatAnalysis {

    /** 峰值判定：窗口能量超过前后 1s 邻域中位数的倍数——局部对比而非全曲均值，
     *  连续噪声（粉噪/白噪）全曲均匀，局部对比检不出峰，正好用来拒绝无节拍素材。 */
    private static final double PEAK_RATIO = 2.2;
    /** 相邻节拍最小间隔（秒）：防同一次打击被拆成两拍。 */
    private static final double MIN_GAP = 0.25;
    /** 分析只取低频打击乐段（Hz）。 */
    private static final int HIGH_HZ = 300;
    private static final int WINDOW = 512;
    /** 邻域半径（秒）：峰值要显著高于邻域才是一拍。 */
    private static final double NEIGHBOR_SEC = 1.0;

    private final ProductionProcessService processes;

    /** tempo = 平均节拍间隔（秒）；times = 逐拍时刻。 */
    public record Beats(double tempo, List<Double> times, String source) { }

    /**
     * 分析一条 BGM：先 ffmpeg 抽低频单声道 wav，再能量包络找峰。
     *
     * <p>分析失败抛出——调用方决定是否回退无节拍模式；静默给空节拍
     * 会让视觉同步悄悄失效，那比失败更难排查。
     */
    public Beats analyze(ProductionRunVo run, Path work, Path bgm) {
        Path wav = work.resolve("beat-analysis.wav");
        var result = processes.run(run, new org.dromara.heron.domain.bo.ProductionProcessBo(
            "ffmpeg", List.of("-v", "error", "-i", bgm.toAbsolutePath().toString(),
                "-af", "highpass=f=60,lowpass=f=" + HIGH_HZ + ",aresample=44100", "-ac", "1",
                "-t", "300", "beat-analysis.wav", "-y"), work, java.time.Duration.ofSeconds(60)));
        if (result.exitCode() != 0) {
            throw new HeronBusinessException(503, "beat_analysis_failed", "BGM 节拍分析失败：低频段提取失败");
        }
        try (var stream = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(wav))) {
            var audio = AudioSystem.getAudioInputStream(stream);
            var format = audio.getFormat();
            if (format.getSampleSizeInBits() != 16 || format.getChannels() != 1) {
                throw new HeronBusinessException(503, "beat_analysis_failed", "节拍分析只支持 16bit 单声道 wav");
            }
            byte[] raw = audio.readAllBytes();
            int rate = (int) format.getSampleRate();
            int count = raw.length / 2;
            List<Double> rms = new ArrayList<>(count / WINDOW + 1);
            for (int i = 0; i + WINDOW <= count; i += WINDOW) {
                double energy = 0;
                for (int j = 0; j < WINDOW; j++) {
                    int sample = (short) ((raw[(i + j) * 2] & 0xff) | (raw[(i + j) * 2 + 1] << 8));
                    energy += sample * sample;
                }
                rms.add(Math.sqrt(energy / WINDOW));
            }
            double[] envelope = rms.stream().mapToDouble(Double::doubleValue).toArray();
            double step = WINDOW / (double) rate;
            int neighbor = Math.max(1, (int) Math.round(NEIGHBOR_SEC / step));
            List<Double> beats = new ArrayList<>();
            double last = -MIN_GAP;
            for (int i = 0; i < envelope.length; i++) {
                double t = i * step;
                if (t - last < MIN_GAP) {
                    continue;
                }
                // 局部邻域中位数：峰值必须显著高于邻域，连续噪声局部也均匀，检不出峰
                double local = neighborMedian(envelope, i, neighbor);
                if (envelope[i] > local * PEAK_RATIO && envelope[i] > 100) {
                    beats.add(round3(t));
                    last = t;
                }
            }
            if (beats.size() < 4) {
                throw new HeronBusinessException(509, "beat_analysis_failed", "节拍数过少，BGM 可能不适合节拍驱动视觉");
            }
            double span = beats.get(beats.size() - 1) - beats.get(0);
            double tempo = span > 0 ? span / (beats.size() - 1) : 0;
            return new Beats(round3(tempo), List.copyOf(beats), bgm.getFileName().toString());
        } catch (UnsupportedAudioFileException | IOException e) {
            throw new HeronBusinessException(503, "beat_analysis_failed", "节拍分析音频不可读", e);
        }
    }

    /** 邻域中位数：取 [i-neighbor, i+neighbor] 去掉自身后的中位，作为「背景」参考。 */
    private static double neighborMedian(double[] envelope, int index, int neighbor) {
        List<Double> locals = new ArrayList<>();
        for (int j = Math.max(0, index - neighbor); j <= Math.min(envelope.length - 1, index + neighbor); j++) {
            if (j != index) {
                locals.add(envelope[j]);
            }
        }
        if (locals.isEmpty()) {
            return 0;
        }
        locals.sort(Double::compare);
        return locals.get(locals.size() / 2);
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
