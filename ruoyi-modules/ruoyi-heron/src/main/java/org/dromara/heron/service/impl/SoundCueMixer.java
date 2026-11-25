package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 声音提示渲染与总线混入。
 *
 * <p>音效先单独渲染成一条定长 WAV，避免调用方为每个提示维护 ffmpeg 输入编号。
 * 滤镜图写入本次尝试目录的受控脚本，避免触发生产进程单参数长度上限。
 * 所有提示在原素材基础上只衰减 3 dB；只要与真实字幕句窗相交，整条提示衰减 6 dB，
 * 不改变旁白增益。缺素材在规划与渲染前各检查一次，均留下显式 SKIP。</p>
 */
public final class SoundCueMixer {

    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern INPUT_LABEL = Pattern.compile("\\[(?:[0-9]+:a|[a-zA-Z0-9_]+)\\]");
    private static final String BED_FILE = "sfx-bed.wav";
    static final String FILTER_SCRIPT = "sfx-bed.filter";

    private SoundCueMixer() { }

    /** 渲染结果；bed 为 null 表示所有提示都显式跳过，而不是静默成功。 */
    public record Result(Path bed, List<SoundCue.Check> checks) {
        public Result {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /**
     * 把规划结果渲染为与成片等长的音效床。
     *
     * @param processes        受控进程服务
     * @param run              生产运行上下文
     * @param work             当前工作目录
     * @param plan             {@link SoundCue#plan} 的结果
     * @param narrationWindows 实际字幕句窗
     * @param durationMs       成片实测时长
     * @return 音效床与每个提示的 PASS/SKIP
     */
    public static Result render(ProductionProcessService processes, ProductionRunVo run, Path work,
                                SoundCue.Plan plan, List<SoundCue.Window> narrationWindows, long durationMs) {
        if (processes == null || run == null || work == null || plan == null || narrationWindows == null
            || durationMs <= 0) {
            throw new HeronBusinessException(409, "news_sound_cue_alignment", "音效渲染缺少有效的运行上下文或成片时长");
        }
        List<SoundCue.Check> checks = new ArrayList<>(plan.checks());
        List<SoundCue.Cue> renderable = new ArrayList<>();
        List<Long> durations = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        for (SoundCue.Cue cue : plan.cues()) {
            Path asset = cue.asset();
            if (!Files.isRegularFile(asset) || !Files.isReadable(asset)) {
                checks.add(SoundCueMixer.missing(cue, "素材在规划后被删除或不可读"));
                continue;
            }
            double seconds = probeSeconds(processes, run, work, asset);
            long cueDuration = Math.min(Math.round(seconds * 1000), durationMs - cue.atMs());
            if (cueDuration <= 0) {
                checks.add(new SoundCue.Check("SKIP", "SFX_OUTSIDE_TIMELINE", cue.type(), cue.sceneId(),
                    asset.getFileName().toString(), "音效起点超出实测成片窗口"));
                continue;
            }
            renderable.add(cue);
            durations.add(cueDuration);
            inputs.addAll(List.of("-i", asset.toString()));
        }
        if (renderable.isEmpty()) {
            return new Result(null, checks);
        }

        List<String> command = new ArrayList<>();
        command.addAll(List.of("-y", "-loglevel", "error"));
        command.addAll(inputs);
        StringBuilder filter = new StringBuilder();
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < renderable.size(); index++) {
            SoundCue.Cue cue = renderable.get(index);
            String label = cueLabel(cue, index);
            filter.append(cueFilter(index, cue, durations.get(index), narrationWindows)).append(';');
            labels.add("[" + label + "]");
            checks.add(new SoundCue.Check("PASS", "SFX_RENDERED", cue.type(), cue.sceneId(),
                assetName(cue), "已按实际窗口 " + cue.atMs() + "ms 渲染；旁白相交时衰减 6 dB，否则 3 dB"));
        }
        filter.append(String.join("", labels)).append("amix=inputs=").append(labels.size())
            .append(":duration=longest:dropout_transition=0:normalize=0,apad,atrim=duration=")
            .append(seconds(durationMs)).append(',').append(AudioLoudness.limiter()).append("[sfx]");
        Path filterScript = work.resolve(FILTER_SCRIPT);
        try {
            Files.writeString(filterScript, filter.toString());
        } catch (IOException e) {
            throw new HeronBusinessException(503, "news_sound_cue_render_failed", "音效滤镜脚本写入失败", e);
        }
        command.addAll(List.of("-filter_complex_script", FILTER_SCRIPT, "-map", "[sfx]",
            "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le", BED_FILE));
        ProductionProcessVo result = processes.run(run,
            new ProductionProcessBo("ffmpeg", command, work, RENDER_TIMEOUT));
        Path bed = work.resolve(BED_FILE);
        if (result.exitCode() != 0 || result.timedOut() || !Files.isRegularFile(bed) || size(bed) <= 0) {
            // 编码失败不能退化成“没有音效但成功”；与缺素材的显式 SKIP 是两种结果。
            throw new HeronBusinessException(503, "news_sound_cue_render_failed", "音效床渲染失败：" + tail(result.stderr()));
        }
        return new Result(bed, checks);
    }

    /**
     * 生成单条提示的滤镜；时间从提示的实际 atMs 延迟，增益只有 −3/−6 dB 两档。
     */
    static String cueFilter(int inputIndex, SoundCue.Cue cue, long durationMs,
                            List<SoundCue.Window> narrationWindows) {
        boolean overlapsNarration = narrationWindows.stream().anyMatch(window ->
            cue.atMs() < window.endMs() && cue.atMs() + durationMs > window.startMs());
        double gainDb = overlapsNarration ? SoundCue.narrationGainDb() : SoundCue.gapGainDb();
        long fadeMs = Math.min(20, durationMs / 4);
        return "[" + inputIndex + ":a]atrim=duration=" + seconds(durationMs)
            + ",asetpts=PTS-STARTPTS,volume=" + AudioLoudness.fmt(AudioLoudness.factor(gainDb))
            + ",afade=t=in:st=0:d=" + seconds(fadeMs)
            + ",afade=t=out:st=" + seconds(durationMs - fadeMs) + ":d=" + seconds(fadeMs)
            + ",adelay=" + cue.atMs() + ":all=1[" + cueLabel(cue, inputIndex) + "]";
    }

    /**
     * 把定长音效床追加到现有旁白/BGM 总线。
     *
     * @param narrationBgmFilter 现有以 {@code [mix]} 结尾的滤镜链
     * @param sfxInputLabel      ffmpeg 音效床输入标签，例如 {@code [2:a]}
     * @return 保留原总线、相加后再次限幅的新滤镜链
     */
    public static String mixIntoBus(String narrationBgmFilter, String sfxInputLabel) {
        if (narrationBgmFilter == null || !narrationBgmFilter.endsWith("[mix]")
            || sfxInputLabel == null || !INPUT_LABEL.matcher(sfxInputLabel).matches()) {
            throw new HeronBusinessException(409, "news_sound_cue_alignment", "音效总线标签或以 [mix] 结尾的滤镜链非法");
        }
        String base = narrationBgmFilter.substring(0, narrationBgmFilter.length() - "[mix]".length()) + "[base]";
        return base + ";" + "[base]" + sfxInputLabel
            + "amix=inputs=2:duration=first:dropout_transition=0:normalize=0,"
            + AudioLoudness.limiter() + "[mix]";
    }

    private static String cueLabel(SoundCue.Cue cue, int index) {
        return "cue" + index + "_" + cue.type().name().toLowerCase(Locale.ROOT);
    }

    private static double probeSeconds(ProductionProcessService processes, ProductionRunVo run, Path work, Path asset) {
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffprobe",
            List.of("-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1",
                asset.toString()), work, PROBE_TIMEOUT));
        if (result.exitCode() != 0 || result.timedOut()) {
            throw new HeronBusinessException(503, "news_sound_cue_probe_failed", "音效时长探测失败：" + tail(result.stderr()));
        }
        try {
            double seconds = Double.parseDouble(result.stdout().trim());
            if (!Double.isFinite(seconds) || seconds <= 0) {
                throw new NumberFormatException();
            }
            return seconds;
        } catch (NumberFormatException e) {
            throw new HeronBusinessException(503, "news_sound_cue_probe_failed", "音效时长不是正数：" + result.stdout());
        }
    }

    private static SoundCue.Check missing(SoundCue.Cue cue, String reason) {
        return new SoundCue.Check("SKIP", "MISSING_SFX_ASSET", cue.type(), cue.sceneId(), assetName(cue), reason);
    }

    private static String assetName(SoundCue.Cue cue) {
        return cue.asset().getFileName().toString();
    }

    private static String seconds(long millis) {
        return AudioLoudness.fmt(millis / 1000.0);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) return "（无输出）";
        return text.length() <= 200 ? text : text.substring(text.length() - 200);
    }
}
