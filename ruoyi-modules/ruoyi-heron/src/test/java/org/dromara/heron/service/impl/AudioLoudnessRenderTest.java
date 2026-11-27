package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.media.AudioLoudness;
import org.dromara.heron.service.impl.shared.production.NewsNarrationTiming;
import org.dromara.heron.service.impl.shared.production.NewsProductionPipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 响度链路的真实渲染验证：拿仓库里真实的配乐，走一遍管线上的「测 → 调 → 验」。
 *
 * <p>用真素材而不是合成信号才有意义：仓库里最响的那首 BGM 自身 −10 LUFS、真峰值还会顶到
 * 0 dBFS 以上，固定增益遇上它必然出事。这条测试就是防止有人把增益改回固定值。
 */
@Tag("render")
class AudioLoudnessRenderTest {

    @TempDir
    Path work;

    private static final NewsNarrationTiming.Span CLOSING =
        new NewsNarrationTiming.Span("closing-slot", 28000, 41500, false);

    private static final int MIX_ATTEMPTS = 3;

    @Test
    void convergesToDeliverySpecWithLoudestBgm() throws Exception {
        Path bgm = repo().resolve(Path.of("data", "audio", "bgm", "stereotype-news.mp3"));
        assertTrue(Files.isRegularFile(bgm), "仓库里应当有最响的那首配乐：" + bgm);

        Path voice = syntheticVoice("voice.wav");
        AudioLoudness.Measurement voiceMeasured = measure(List.of("-i", voice.toString()));
        AudioLoudness.Measurement bgmMeasured =
            measure(List.of("-stream_loop", "-1", "-i", bgm.toString()), NewsProductionPipeline.bgmEnvelope(CLOSING));

        double bgmGain = AudioLoudness.bgmGain(bgmMeasured);
        double gain = AudioLoudness.voiceGain(voiceMeasured);
        AudioLoudness.Measurement finalMeasured = null;
        for (int attempt = 1; attempt <= MIX_ATTEMPTS; attempt++) {
            Path mixed = work.resolve("mixed-" + attempt + ".wav");
            mix(voice, bgm, NewsProductionPipeline.bgmEnvelope(CLOSING), gain, bgmGain, mixed);
            finalMeasured = measure(List.of("-i", mixed.toString()));
            if (finalMeasured.deliveryOk()) {
                break;
            }
            double residual = AudioLoudness.TARGET_LUFS - finalMeasured.integratedLufs();
            if (Math.abs(residual) < 0.2) {
                break;
            }
            gain += residual;
        }

        assertTrue(finalMeasured.loudnessOk(),
            "成片响度 " + finalMeasured.integratedLufs() + " LUFS 不在 "
                + AudioLoudness.TARGET_LUFS + " ± " + AudioLoudness.TOLERANCE_LUFS);
        assertTrue(finalMeasured.peakOk(),
            "成片真峰值 " + finalMeasured.truePeakDb() + " dBTP 越过了 " + AudioLoudness.MAX_TRUE_PEAK);
    }

    @Test
    void bgmSitsBelowVoiceAfterGain() throws Exception {
        Path bgm = repo().resolve(Path.of("data", "audio", "bgm", "stereotype-news.mp3"));
        assertTrue(Files.isRegularFile(bgm), bgm.toString());

        Path voice = syntheticVoice("voice.wav");
        AudioLoudness.Measurement voiceMeasured = measure(List.of("-i", voice.toString()));
        AudioLoudness.Measurement bgmMeasured =
            measure(List.of("-stream_loop", "-1", "-i", bgm.toString()), NewsProductionPipeline.bgmEnvelope(CLOSING));

        // 配乐落到 −28，旁白落到 −16：两者相差 12 dB
        double voiceAfter = voiceMeasured.integratedLufs() + AudioLoudness.voiceGain(voiceMeasured);
        double bgmAfter = bgmMeasured.integratedLufs() + AudioLoudness.bgmGain(bgmMeasured);

        assertTrue(Math.abs(voiceAfter - AudioLoudness.TARGET_LUFS) < 0.01,
            "旁白应落在目标响度，实际 " + voiceAfter);
        assertTrue(Math.abs(bgmAfter - (AudioLoudness.TARGET_LUFS + AudioLoudness.BGM_OFFSET_LUFS)) < 0.01,
            "配乐应比成片低 12 dB，实际 " + bgmAfter);
    }

    @Test
    void legacyFixedGainIsNotSafeForQuietVoices() throws Exception {
        Path bgm = repo().resolve(Path.of("data", "audio", "bgm", "stereotype-news.mp3"));
        Path voice = syntheticVoice("quiet.wav");

        // 旧口径的片尾处理：增益固定 0.40，不看旁白实际多响
        AudioLoudness.Measurement legacyClosing = measure(
            List.of("-stream_loop", "-1", "-i", bgm.toString()),
            "atrim=start=28:end=41.5,asetpts=PTS-STARTPTS,volume=0.40");
        AudioLoudness.Measurement voiceRaw = measure(List.of("-i", voice.toString()));

        // 固定增益不管旁白多响：轻旁白下片尾配乐直接盖过人声
        assertTrue(legacyClosing.integratedLufs() > voiceRaw.integratedLufs(),
            "旧口径下片尾配乐 " + legacyClosing.integratedLufs() + " LUFS 应当压过轻旁白 "
                + voiceRaw.integratedLufs() + " LUFS；若已不压过，说明这个回归测试的前提变了");
    }

    private static Path repo() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    /** 造一段有语音节奏的旁白：句子之间留空、峰值高于均值，接近人声的波峰因数。 */
    private Path syntheticVoice(String name) throws Exception {
        Path voice = work.resolve(name);
        ffmpeg(List.of("-y", "-f", "lavfi", "-i",
            "aevalsrc=if(lt(mod(t\\,3)\\,1.1)\\,0.30*sin(2*PI*180*t)*exp(-2.5*mod(t\\,3))\\,0)"
                + ":s=48000:d=41.5", voice.toString()));
        return voice;
    }

    private void mix(Path voice, Path bgm, String envelope, double voiceGain, double bgmGain, Path out)
            throws Exception {
        ffmpeg(List.of("-y", "-i", voice.toString(), "-stream_loop", "-1", "-i", bgm.toString(),
            "-filter_complex", NewsProductionPipeline.mixFilter(envelope, voiceGain, bgmGain, true),
            "-map", "[mix]", out.toString()));
    }

    /** 用 loudnorm 滤镜量一段输入，取输入侧的实测值。 */
    private AudioLoudness.Measurement measure(List<String> inputArgs) {
        return measure(inputArgs, null);
    }

    private AudioLoudness.Measurement measure(List<String> inputArgs, String preFilter) {
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostats"));
        args.addAll(inputArgs);
        String filter = "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json";
        args.addAll(List.of("-vn", "-af", preFilter == null ? filter : preFilter + "," + filter, "-f", "null", "-"));
        Path log = work.resolve("measure-" + System.nanoTime() + ".log");
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("响度测量超时");
            }
            assertTrue(process.exitValue() == 0, Files.readString(log));
            return AudioLoudness.parse(Files.readString(log));
        } catch (Exception e) {
            throw new IllegalStateException("响度测量失败", e);
        }
    }

    private void ffmpeg(List<String> args) throws Exception {
        Path log = work.resolve("ffmpeg-" + System.nanoTime() + ".log");
        List<String> full = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error"));
        full.addAll(args);
        Process process = new ProcessBuilder(full).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("ffmpeg 超时");
        }
        assertTrue(process.exitValue() == 0, Files.readString(log));
    }
}
