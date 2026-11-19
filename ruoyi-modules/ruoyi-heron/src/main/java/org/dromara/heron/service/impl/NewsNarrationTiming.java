package org.dromara.heron.service.impl;

import cn.hutool.core.util.EscapeUtil;
import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 以实际字幕文字和句界定位分镜，不以模型声明时长推算配音。 */
final class NewsNarrationTiming {
    private static final long MAX_INTERPOLATION_MS = 1000;
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?][”’\"']?$");

    private NewsNarrationTiming() { }

    /** 一个画面窗口；cueInterpolated 表示起点只在短字幕内部插值，不能冒称词级精度。 */
    record Span(String id, long startMs, long endMs, boolean cueInterpolated) { }

    private record Cue(int startOffset, int endOffset, long startMs, long endMs) { }

    /** 配音与对齐共用同一段文本；显式句号让服务端保留段落边界。 */
    static List<String> spoken(JsonNode script) {
        List<String> text = new ArrayList<>();
        for (JsonNode scene : script.path("scenes")) {
            text.add(sentence(scene.path("lead").asString("") + "。" + scene.path("body").asString("")));
        }
        text.add(sentence(script.path("closing").asString("")));
        return List.copyOf(text);
    }

    /** 文本必须完整对应，长cue不能靠比例猜测；尾窗覆盖完整结尾及音频尾静音。 */
    static List<Span> align(JsonNode script, JsonNode subtitles, long audioMs) {
        List<String> segments = spoken(script);
        if (audioMs <= 0 || segments.size() < 2 || !subtitles.isArray() || subtitles.isEmpty()) {
            throw invalid("缺少可对齐的口播段落或字幕");
        }
        List<Cue> cues = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        long previousStart = -1;
        long previousEnd = -1;
        for (JsonNode subtitle : subtitles) {
            long start = subtitle.path("startMs").asLong(-1);
            long end = subtitle.path("endMs").asLong(-1);
            if (start < 0 || end <= start || start < previousStart || start < previousEnd - 250
                || start >= audioMs || end > audioMs + 250) {
                throw invalid("字幕时间倒退、重叠过长或超出配音");
            }
            String text = normalized(subtitle.path("text").asString(""));
            if (!text.isEmpty()) {
                cues.add(new Cue(actual.length(), actual.length() + text.length(), start, Math.min(end, audioMs)));
                actual.append(text);
            }
            previousStart = start;
            previousEnd = end;
        }
        List<String> normalizedSegments = segments.stream().map(NewsNarrationTiming::normalized).toList();
        if (normalizedSegments.stream().anyMatch(String::isEmpty)
            || !String.join("", normalizedSegments).contentEquals(actual)) {
            throw invalid("字幕文字与口播稿不一致，不能确定分镜句界");
        }
        List<Long> starts = new ArrayList<>();
        List<Boolean> interpolated = new ArrayList<>();
        int offset = 0;
        for (String text : normalizedSegments) {
            int boundary = offset;
            Cue cue = cues.stream().filter(item -> item.startOffset() <= boundary && boundary < item.endOffset())
                .findFirst().orElseThrow(() -> invalid("字幕缺少段落对应区间"));
            boolean inside = boundary != cue.startOffset();
            if (inside && cue.endMs() - cue.startMs() > MAX_INTERPOLATION_MS) {
                throw invalid("段落句界跨越长字幕，请按完整句子重新配音");
            }
            long start = boundary == 0 ? 0 : cue.startMs() + Math.round(
                (double) (boundary - cue.startOffset()) * (cue.endMs() - cue.startMs())
                    / (cue.endOffset() - cue.startOffset()));
            if (!starts.isEmpty() && start <= starts.get(starts.size() - 1)) {
                throw invalid("字幕精度不足以区分相邻分镜");
            }
            starts.add(start);
            interpolated.add(inside);
            offset += text.length();
        }
        List<Span> timeline = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            String id = index == starts.size() - 1 ? "closing-slot" : "scene-" + (index + 1);
            long end = index + 1 < starts.size() ? starts.get(index + 1) : audioMs;
            timeline.add(new Span(id, starts.get(index), end, interpolated.get(index)));
        }
        return List.copyOf(timeline);
    }

    private static String sentence(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() || SENTENCE_END.matcher(stripped).find() ? stripped : stripped + "。";
    }

    private static String normalized(String text) {
        // 复用已有归一：中文标点和空白可变化，1.2 与 12 的数字锚点必须保持不同。
        return NewsDedup.normalizedTitle(EscapeUtil.unescapeHtml4(text.replaceAll("<[^>]*>", "")));
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(409, "news_audio_alignment", reason);
    }
}
