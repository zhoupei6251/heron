package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.bo.ProductionProcessBo;
import org.dromara.heron.domain.vo.ProductionProcessVo;
import org.dromara.heron.domain.vo.ProductionRunVo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 成片画面信号：黑屏与冻结帧。
 *
 * <p>为什么要有这一层：技术闸只证明「这是一个能播的 mp4」，版式闸（hyperframes check）
 * 看的是排版与对比度。两者都答不上「画面到底有没有在动」——而短视频最常见的失败不是画错，
 * 是不动：一张静止的图配一段念稿，观感就是 PPT。
 *
 * <p>黑场拦、冻结只记：黑场是成片本身不成立，冻结是观感问题，两者不是一个量级。
 *
 * <p>阈值是量出来的，不是拍的：三套数据风格都是暗底（亮度 13～20/255），
 * 像素黑阈取 3%（约 7.7/255）而不是默认的 10%（25.5/255）——默认值会把深色底的正片整段判成黑屏。
 * 实测 4 支真实样片零误报，合成的「暗底 + 纯黑 + 暗底」片能准确报出那段黑场。
 */
final class VideoSignalCheck {

    /** 黑场最短时长：短于此的是转场，不算黑屏。 */
    static final double BLACK_MIN_SECONDS = 1.0;

    /** 冻结最短时长：短于此的是镜头内的小停顿。 */
    static final double FREEZE_MIN_SECONDS = 2.0;

    /** 冻结判定阈值：相邻帧差异小于这个 dB 视为同一画面。 */
    private static final String FREEZE_NOISE = "-60dB";

    /** 像素黑阈：占亮度的比例。低于它才算黑像素。 */
    private static final String BLACK_PIXEL_RATIO = "0.03";

    /** 黑场占比：低于它不算整段黑屏。 */
    private static final String BLACK_PICTURE_RATIO = "0.98";

    private static final String FILTER = "blackdetect=d=" + BLACK_MIN_SECONDS
        + ":pic_th=" + BLACK_PICTURE_RATIO + ":pix_th=" + BLACK_PIXEL_RATIO
        + ",freezedetect=n=" + FREEZE_NOISE + ":d=" + FREEZE_MIN_SECONDS;

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private static final Pattern BLACK = Pattern.compile("black_start:([0-9.]+)\\s+black_end:([0-9.]+)");
    private static final Pattern FREEZE_START = Pattern.compile("freeze_start:\\s*([0-9.]+)");
    private static final Pattern FREEZE_END = Pattern.compile("freeze_end:\\s*([0-9.]+)");

    private VideoSignalCheck() { }

    /** 一段画面异常。 */
    record Span(double start, double end) {

        double seconds() {
            return Math.max(0, end - start);
        }
    }

    /** 一次检测的结果。 */
    record Result(List<Span> black, List<Span> freeze, double videoSeconds) {

        double longestBlack() {
            return longest(black);
        }

        double longestFreeze() {
            return longest(freeze);
        }

        private static double longest(List<Span> spans) {
            double max = 0;
            for (Span span : spans) {
                max = Math.max(max, span.seconds());
            }
            return max;
        }
    }

    /**
     * 跑一遍画面信号检测。
     *
     * @param videoSeconds 成片时长，用来收尾没有 freeze_end 的那段冻结
     */
    static Result run(ProductionProcessService processes, ProductionRunVo run, Path work, String relativeFile,
                      double videoSeconds) {
        List<String> args = List.of("-hide_banner", "-nostats", "-i", relativeFile,
            "-vf", FILTER, "-an", "-f", "null", "-");
        ProductionProcessVo result = processes.run(run, new ProductionProcessBo("ffmpeg", args, work, TIMEOUT));
        if (result.exitCode() != 0 || result.timedOut()) {
            throw new HeronBusinessException(503, "news_video_signal_failed",
                "画面信号检测没有跑通：" + tail(result.stderr()));
        }
        return parse(result.stderr(), videoSeconds);
    }

    /**
     * 从 ffmpeg 的滤镜日志里取黑场与冻结段。
     *
     * <p>blackdetect 把起止与时长打在一行里；freezedetect 分三行打，先 start 再 end，
     * 视频在冻结中结束时不会补 end——那一段按成片时长收尾。
     */
    static Result parse(String text, double videoSeconds) {
        List<Span> black = new ArrayList<>();
        List<Span> freeze = new ArrayList<>();
        Matcher blackMatcher = BLACK.matcher(text);
        while (blackMatcher.find()) {
            black.add(new Span(Double.parseDouble(blackMatcher.group(1)),
                Double.parseDouble(blackMatcher.group(2))));
        }
        double pendingStart = -1;
        for (String line : text.split("\\R")) {
            Matcher startMatcher = FREEZE_START.matcher(line);
            if (startMatcher.find()) {
                pendingStart = Double.parseDouble(startMatcher.group(1));
                continue;
            }
            Matcher endMatcher = FREEZE_END.matcher(line);
            if (endMatcher.find() && pendingStart >= 0) {
                freeze.add(new Span(pendingStart, Double.parseDouble(endMatcher.group(1))));
                pendingStart = -1;
            }
        }
        if (pendingStart >= 0) {
            freeze.add(new Span(pendingStart, videoSeconds));
        }
        return new Result(List.copyOf(black), List.copyOf(freeze), videoSeconds);
    }

    /**
     * 第一段致命黑场；没有就返回 null。
     *
     * <p>致命口径就一条：检出的黑场已经在检测阈值上了（至少 1 秒、98% 画面是黑像素），
     * 短于此的转场根本不会被报上来。整段纯黑一秒以上，画面上没有内容可讲，
     * 这样的成片不该再往审稿环节走。
     */
    static Span blockingBlack(Result result) {
        return result.black().isEmpty() ? null : result.black().get(0);
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "（无输出）";
        }
        return text.length() <= 200 ? text : text.substring(text.length() - 200);
    }
}
