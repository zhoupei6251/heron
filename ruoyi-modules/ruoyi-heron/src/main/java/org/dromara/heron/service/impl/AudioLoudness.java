package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 成片响度口径。
 *
 * <p>为什么要有这一层：配乐过去是固定增益（正文 0.22、片尾 0.40）直接叠加，
 * 而仓库里 6 首 BGM 自身响度差了 8 dB——最响的那首在片尾实测压过旁白 1.3 dB。
 * 固定增益对不同响度的曲目不可能都对，而 {@code amix normalize=0} 的直接叠加
 * 也没有任何检测会拦住峰值越界：不削波只是运气，不是保障。
 *
 * <p>所以改成三步：先测，再按目标算增益，混完复测。
 * 口径：成片 −16 LUFS ±1、真峰值 ≤ −1.5 dBTP；配乐比成片低 12 dB。
 *
 * <p>量出来的值一律是实测值，不做四舍五入以外的修饰；进 {@code checks} 时才取两位小数。
 */
final class AudioLoudness {

    /** 成片目标响度：短视频交付口径。 */
    static final double TARGET_LUFS = -16.0;

    /** 允许偏离：±1 LU。 */
    static final double TOLERANCE_LUFS = 1.0;

    /** 真峰值上限：−1.5 dBTP。 */
    static final double MAX_TRUE_PEAK = -1.5;

    /** 配乐相对成片低多少：12 dB，听感上是背景、不抢话。 */
    static final double BGM_OFFSET_LUFS = -12.0;

    /** 限幅器只限制采样峰值；为真峰值重建预留 0.3 dB，避免重采样或 AAC 后越界。 */
    static final double LIMIT = Math.pow(10, (MAX_TRUE_PEAK - 0.3) / 20.0);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 测量用的 loudnorm 滤镜：只打印输入侧的实测值。 */
    private static final String LOUDNORM = "loudnorm=I=" + fmt(TARGET_LUFS) + ":TP=" + fmt(MAX_TRUE_PEAK)
        + ":LRA=11:print_format=json";

    private static final Duration MEASURE_TIMEOUT = Duration.ofSeconds(180);

    private AudioLoudness() { }

    /**
     * 一条音轨的实测响度。
     *
     * @param integratedLufs 整体响度（LUFS）
     * @param truePeakDb     真峰值（dBTP）
     * @param lra            响度范围
     * @param threshold      响度门限
     */
    record Measurement(double integratedLufs, double truePeakDb, double lra, double threshold) {

        /** 响度是否在目标范围内。 */
        boolean loudnessOk() {
            return Math.abs(integratedLufs - TARGET_LUFS) <= TOLERANCE_LUFS;
        }

        /** 真峰值是否没有越界。 */
        boolean peakOk() {
            return truePeakDb <= MAX_TRUE_PEAK;
        }

        /** 是否满足交付口径。 */
        boolean deliveryOk() {
            return loudnessOk() && peakOk();
        }
    }

    /**
     * 旁白需要的增益：把测得的整体响度推到目标。
     *
     * <p>只算增益不算峰值：峰值交给限幅器，超出部分由限幅器压掉。
     */
    static double voiceGain(Measurement measurement) {
        return TARGET_LUFS - measurement.integratedLufs();
    }

    /** 配乐需要的增益：把配乐推到比成片低 12 dB。 */
    static double bgmGain(Measurement measurement) {
        return (TARGET_LUFS + BGM_OFFSET_LUFS) - measurement.integratedLufs();
    }

    /** 限幅器滤镜：把采样峰值压在真峰值上限以下，且不做自动电平补偿。 */
    static String limiter() {
        return "alimiter=limit=" + fmt(LIMIT) + ":level=disabled";
    }

    /** dB 增益转线性系数；ffmpeg 的 volume 滤镜用线性系数更不容易被版本差异坑到。 */
    static double factor(double gainDb) {
        return Math.pow(10, gainDb / 20.0);
    }

    /** 测量一个文件里的音轨。 */
    static Measurement measureFile(ProductionProcessService processes, ProductionRunVo run, Path work,
                                   String relativeFile) {
        return measure(processes, run, work, List.of("-i", relativeFile), LOUDNORM);
    }

    /**
     * 测量配乐在真实包络下的响度。
     *
     * <p>必须连包络一起测：配乐在正文与片尾的音量不同，只测原始曲目会算错增益。
     */
    static Measurement measureBgm(ProductionProcessService processes, ProductionRunVo run, Path work,
                                  Path bgm, String envelope) {
        return measure(processes, run, work,
            List.of("-stream_loop", "-1", "-i", bgm.toAbsolutePath().toString()),
            envelope + "," + LOUDNORM);
    }

    private static Measurement measure(ProductionProcessService processes, ProductionRunVo run, Path work,
                                       List<String> inputArgs, String filterChain) {
        List<String> args = new ArrayList<>(List.of("-hide_banner", "-nostats"));
        args.addAll(inputArgs);
        // 只量音轨：解码视频纯属浪费时间，成片是 1080×1920 的整条片子
        args.addAll(List.of("-vn", "-af", filterChain, "-f", "null", "-"));
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg", args, work, MEASURE_TIMEOUT));
        if (result.exitCode() != 0 || result.timedOut()) {
            throw new HeronBusinessException(503, "news_audio_loudness_failed",
                "响度测量没有跑通：" + tail(result.stderr()));
        }
        return parse(result.stderr());
    }

    /**
     * 从 loudnorm 打印的 JSON 里取实测值。
     *
     * <p>按 {@code input_i} 定位而不是取第一对花括号：ffmpeg 的日志里别处也可能有花括号。
     */
    static Measurement parse(String text) {
        int marker = text.indexOf("\"input_i\"");
        if (marker < 0) {
            throw new HeronBusinessException(503, "news_audio_loudness_failed", "响度测量结果里没有实测值");
        }
        int start = text.lastIndexOf('{', marker);
        int end = text.indexOf('}', marker);
        if (start < 0 || end < 0) {
            throw new HeronBusinessException(503, "news_audio_loudness_failed", "响度测量结果不是 JSON");
        }
        JsonNode node;
        try {
            node = JSON.readTree(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "news_audio_loudness_failed", "响度测量结果不是 JSON");
        }
        return new Measurement(number(node, "input_i"), number(node, "input_tp"),
            number(node, "input_lra"), number(node, "input_thresh"));
    }

    private static double number(JsonNode node, String field) {
        String raw = node.path(field).asString("").trim();
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new HeronBusinessException(503, "news_audio_loudness_failed", "响度测量缺 " + field);
        }
        if (!Double.isFinite(value)) {
            // 静音轨的 input_i 是 -inf：那是「没有声音」，不是「响度为零」。
            throw new HeronBusinessException(503, "news_audio_loudness_failed",
                "响度测量得到 " + field + "=" + raw + "，音轨可能是空的");
        }
        return value;
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "（无输出）";
        }
        return text.length() <= 200 ? text : text.substring(text.length() - 200);
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
