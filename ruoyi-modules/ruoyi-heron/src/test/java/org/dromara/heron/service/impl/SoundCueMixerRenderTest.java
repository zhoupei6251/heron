package org.dromara.heron.service.impl;

import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 用仓库真实音效与合成旁白验证音效床可混入现有总线且不破坏成片响度。 */
@Tag("render")
class SoundCueMixerRenderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<SoundCue.Window> SCENES = List.of(
        new SoundCue.Window("scene-1", 0, 1_000),
        new SoundCue.Window("scene-2", 1_000, 2_000),
        new SoundCue.Window("closing-slot", 2_000, 3_000));
    private static final List<SoundCue.Window> NARRATION = List.of(
        new SoundCue.Window("sentence-1", 100, 450),
        new SoundCue.Window("sentence-2", 1_100, 1_450),
        new SoundCue.Window("sentence-3", 2_100, 2_450));

    @TempDir
    Path work;

    @Test
    void rendersAudibleCueBedAndKeepsFinalLoudness() throws Exception {
        Path scriptPath = work.resolve("script.json");
        Files.writeString(scriptPath, """
            {"scenes":[
              {"on_screen_text":"12.5%","lead":"十二点五","body":"增长"},
              {"on_screen_text":"趋势","lead":"趋势","body":"向上"}
            ],"closing":"结论明确"}
            """);
        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION, JSON.readTree(Files.readString(scriptPath)),
            repo().resolve("data/audio/sfx"));
        assertTrue(plan.checks().isEmpty());

        ProductionRunVo run = run();
        LocalProduction production = production(run);
        SoundCueMixer.Result result = SoundCueMixer.render(production.processes(), run, production.work(),
            plan, NARRATION, 3_000);

        assertNotNull(result.bed());
        assertTrue(Files.size(result.bed()) > 1_000);
        assertEquals(3.0, probeDuration(result.bed()), 0.03);
        AudioLoudness.Measurement bed = measure(List.of("-i", result.bed().toString()), null);
        assertTrue(Double.isFinite(bed.integratedLufs()));
        assertTrue(bed.truePeakDb() <= AudioLoudness.MAX_TRUE_PEAK + 0.2,
            "音效床真峰值越界：" + bed.truePeakDb());
        for (SoundCue.Cue cue : plan.cues()) {
            for (int channel = 0; channel < 2; channel++) {
                assertTrue(rms(result.bed(), cue.atMs() + 20, 200, channel) > 0.0001,
                    "实际提示点声道没有可听信号：" + cue.id() + "@" + cue.atMs() + " channel=" + channel);
            }
        }

        Path voice = syntheticVoice();
        AudioLoudness.Measurement voiceMeasured = measure(List.of("-i", voice.toString()), null);
        double voiceGain = AudioLoudness.voiceGain(voiceMeasured);
        String bus = NewsProductionPipeline.mixFilter("atrim=duration=3.000", voiceGain, 0, false);
        Path mixed = work.resolve("mixed.wav");
        ffmpeg(List.of("-y", "-i", voice.toString(), "-i", result.bed().toString(),
            "-filter_complex", SoundCueMixer.mixIntoBus(bus, "[1:a]"), "-map", "[mix]", mixed.toString()));

        AudioLoudness.Measurement finalMeasured = measure(List.of("-i", mixed.toString()), null);
        assertTrue(finalMeasured.deliveryOk(),
            "加音效后成片 " + finalMeasured.integratedLufs() + " LUFS / " + finalMeasured.truePeakDb() + " dBTP 不达标");
        for (SoundCue.Window scene : SCENES) {
            AudioLoudness.Measurement segment = measure(List.of("-i", mixed.toString()),
                "atrim=start=" + scene.startMs() / 1000.0 + ":end=" + scene.endMs() / 1000.0 + ",asetpts=PTS-STARTPTS");
            assertTrue(segment.loudnessOk(),
                "旁白场景 " + scene.sceneId() + " 加音效后 " + segment.integratedLufs() + " LUFS，不在目标范围");
        }

        Path bgm = repo().resolve("data/audio/bgm/stereotype-news.mp3");
        NewsNarrationTiming.Span closing = new NewsNarrationTiming.Span("closing-slot", 2_000, 3_000, false);
        String envelope = NewsProductionPipeline.bgmEnvelope(closing);
        AudioLoudness.Measurement bgmMeasured = measure(
            List.of("-stream_loop", "-1", "-i", bgm.toString()), envelope);
        double bgmGain = AudioLoudness.bgmGain(bgmMeasured);
        double adjustedVoiceGain = voiceGain;
        AudioLoudness.Measurement withBgmMeasured = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Path withBgm = work.resolve("mixed-bgm-" + attempt + ".wav");
            String bgmBus = NewsProductionPipeline.mixFilter(envelope, adjustedVoiceGain, bgmGain, true);
            ffmpeg(List.of("-y", "-i", voice.toString(), "-stream_loop", "-1", "-i", bgm.toString(),
                "-i", result.bed().toString(), "-filter_complex", SoundCueMixer.mixIntoBus(bgmBus, "[2:a]"),
                "-map", "[mix]", withBgm.toString()));
            withBgmMeasured = measure(List.of("-i", withBgm.toString()), null);
            if (withBgmMeasured.deliveryOk()) break;
            adjustedVoiceGain += AudioLoudness.TARGET_LUFS - withBgmMeasured.integratedLufs();
        }
        assertTrue(withBgmMeasured.deliveryOk(),
            "旁白+BGM+音效三路混音后 " + withBgmMeasured.integratedLufs() + " LUFS 未达标");
    }

    @Test
    void delaysStereoCueOnEveryChannelAndKeepsPreCueSilence() throws Exception {
        SoundCue.Cue whoosh = new SoundCue.Cue("test:whoosh", SoundCue.Type.TRANSITION_WHOOSH,
            "scene-2", 1_000, repo().resolve("data/audio/sfx/whoosh-short.mp3").toAbsolutePath().normalize());
        SoundCue.Plan plan = new SoundCue.Plan(List.of(whoosh), List.of());
        ProductionRunVo run = run();
        LocalProduction production = production(run);

        SoundCueMixer.Result result = SoundCueMixer.render(production.processes(), run, production.work(),
            plan, List.of(), 2_000);

        assertNotNull(result.bed());
        for (int channel = 0; channel < 2; channel++) {
            assertEquals(0.0, rms(result.bed(), 0, 500, channel), 0.000001,
                "立体声提示前声道应静音：channel=" + channel);
            assertTrue(rms(result.bed(), 1_020, 200, channel) > 0.0001,
                "立体声提示目标点应有信号：channel=" + channel);
        }
    }

    @Test
    void returnsNullWhenPlanningSkippedEverything() throws Exception {
        Path emptyAssets = Files.createDirectory(work.resolve("empty-assets"));
        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION,
            JSON.readTree("""
                {"scenes":[{"on_screen_text":"12%"},{"on_screen_text":"趋势"}],"closing":"结论"}
                """), emptyAssets);

        ProductionRunVo run = run();
        LocalProduction production = production(run);
        SoundCueMixer.Result result = SoundCueMixer.render(production.processes(), run, production.work(),
            plan, NARRATION, 3_000);

        assertNull(result.bed());
        assertFalse(result.checks().isEmpty());
        assertTrue(result.checks().stream().allMatch(check -> !check.passed()));
    }

    @Test
    void revalidatesAssetDeletedAfterPlanning() throws Exception {
        Path assets = copyAssets();
        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION,
            JSON.readTree("""
                {"scenes":[{"on_screen_text":"12%"},{"on_screen_text":"趋势"}],"closing":"结论"}
                """), assets);
        assertEquals(3, plan.cues().size());
        SoundCue.Cue tick = plan.cues().stream().filter(cue -> cue.type() == SoundCue.Type.NUMERIC_TICK)
            .findFirst().orElseThrow();
        Files.delete(tick.asset());

        ProductionRunVo run = run();
        LocalProduction production = production(run);
        SoundCueMixer.Result result = SoundCueMixer.render(production.processes(), run, production.work(),
            plan, NARRATION, 3_000);

        assertNotNull(result.bed());
        assertEquals(1, result.checks().stream().filter(check -> !check.passed()).count());
        assertTrue(result.checks().stream().anyMatch(check -> !check.passed()
            && check.type() == SoundCue.Type.NUMERIC_TICK && "MISSING_SFX_ASSET".equals(check.code())));
        assertTrue(result.checks().stream().noneMatch(check -> check.passed()
            && check.type() == SoundCue.Type.NUMERIC_TICK));
        assertEquals(2, result.checks().stream().filter(SoundCue.Check::passed).count());
    }

    @Test
    void rendersMaximumCueGraphThroughProductionValidation() throws Exception {
        List<SoundCue.Window> scenes = new ArrayList<>();
        StringBuilder script = new StringBuilder("{\"scenes\":[");
        for (int index = 0; index < 6; index++) {
            scenes.add(new SoundCue.Window("scene-" + (index + 1), index * 1_000L, (index + 1) * 1_000L));
            if (index > 0) {
                script.append(',');
            }
            script.append("{\"on_screen_text\":\"").append(index + 1).append("%\"}");
        }
        scenes.add(new SoundCue.Window("closing-slot", 6_000, 7_000));
        script.append("],\"closing\":\"结论\"}");
        SoundCue.Plan plan = SoundCue.plan(scenes, List.of(), JSON.readTree(script.toString()),
            repo().resolve("data/audio/sfx"));
        assertEquals(12, plan.cues().size());

        ProductionRunVo run = run();
        LocalProduction production = production(run);
        SoundCueMixer.Result result = SoundCueMixer.render(production.processes(), run, production.work(),
            plan, List.of(), 7_000);

        assertNotNull(result.bed());
        assertEquals(12, result.checks().stream().filter(SoundCue.Check::passed).count());
        assertTrue(Files.readString(production.work().resolve(SoundCueMixer.FILTER_SCRIPT)).length() > 2_048,
            "边界测试必须生成超过生产单参数上限的滤镜图");
    }

    private static double rms(Path wav, long startMs, long durationMs, int channel) throws Exception {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            int frameSize = audio.getFormat().getFrameSize();
            int channels = audio.getFormat().getChannels();
            float sampleRate = audio.getFormat().getSampleRate();
            if (channel < 0 || channel >= channels) {
                throw new IllegalArgumentException("声道不存在：" + channel);
            }
            long skipFrames = Math.round(startMs * sampleRate / 1000.0);
            long frames = Math.min(Math.round(durationMs * sampleRate / 1000.0),
                audio.getFrameLength() - skipFrames);
            if (skipFrames < 0 || frames <= 0 || audio.skip(skipFrames * frameSize) < skipFrames * frameSize) {
                return 0;
            }
            byte[] buffer = new byte[(int) (frames * frameSize)];
            int read = audio.read(buffer);
            if (read <= 0) return 0;
            long sum = 0;
            int samples = 0;
            for (int frame = 0; frame < read / frameSize; frame++) {
                int offset = (frame * channels + channel) * 2;
                short sample = (short) ((buffer[offset + 1] << 8) | (buffer[offset] & 0xff));
                sum += (long) sample * sample;
                samples++;
            }
            return samples == 0 ? 0 : Math.sqrt((double) sum / samples) / 32768.0;
        }
    }

    private final String runToken = UUID.randomUUID().toString();

    private ProductionRunVo run() {
        return new ProductionRunVo(1, ProductionBizType.news_explainer, 1, 1, 1, runToken, "{}");
    }

    private LocalProduction production(ProductionRunVo run) {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(work);
        properties.setGuardEnabled(false);
        ProductionArtifactService artifacts = new ProductionArtifactService(properties, null);
        return new LocalProduction(new ProductionProcessService(properties, artifacts),
            artifacts.prepareWorkDirectory(run));
    }

    private Path copyAssets() throws Exception {
        Path directory = Files.createDirectory(work.resolve("planned-assets"));
        for (String name : List.of("whoosh-short.mp3", "click.mp3", "chime.mp3")) {
            Files.copy(repo().resolve("data/audio/sfx").resolve(name), directory.resolve(name));
        }
        return directory;
    }

    private record LocalProduction(ProductionProcessService processes, Path work) { }

    private Path syntheticVoice() throws Exception {
        Path voice = work.resolve("voice.wav");
        ffmpeg(List.of("-f", "lavfi", "-i",
            "aevalsrc=if(between(mod(t\\,1)\\,0.1\\,0.4)\\,0.25*sin(2*PI*180*t)*exp(-3*mod(t\\,1))\\,0)"
                + ":s=48000:d=3", voice.toString()));
        return voice;
    }

    private static Path repo() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    private double probeDuration(Path file) throws Exception {
        Path log = work.resolve("probe-" + System.nanoTime() + ".log");
        List<String> command = new ArrayList<>(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=nw=1:nk=1", file.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("ffprobe 超时");
        }
        assertTrue(process.exitValue() == 0, Files.readString(log));
        return Double.parseDouble(Files.readString(log).trim());
    }

    private AudioLoudness.Measurement measure(List<String> inputArgs, String preFilter) throws Exception {
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostats"));
        args.addAll(inputArgs);
        String filter = "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json";
        args.addAll(List.of("-vn", "-af", preFilter == null ? filter : preFilter + "," + filter, "-f", "null", "-"));
        Path log = work.resolve("measure-" + System.nanoTime() + ".log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("响度测量超时");
        }
        assertTrue(process.exitValue() == 0, Files.readString(log));
        return AudioLoudness.parse(Files.readString(log));
    }

    private void ffmpeg(List<String> args) throws Exception {
        Path log = work.resolve("ffmpeg-" + System.nanoTime() + ".log");
        List<String> full = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error"));
        full.addAll(args);
        Process process = new ProcessBuilder(full).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("ffmpeg 超时");
        }
        assertTrue(process.exitValue() == 0, Files.readString(log));
    }

}
