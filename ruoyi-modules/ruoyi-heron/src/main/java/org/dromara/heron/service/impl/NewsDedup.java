package org.dromara.heron.service.impl;

import org.dromara.common.json.utils.JsonUtils;
import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BiPredicate;

/** 标题归一、同新闻判定与来源证据合并：手工输入与自动采集共用同一套规则，避免两条线各自漂移。 */
public final class NewsDedup {

    private static final Pattern NUMBERS = Pattern.compile("[0-9]+(?:[.][0-9]+)?");
    private static final int MAX_EVIDENCE_ITEMS = 100;
    private static final int MAX_EVIDENCE_BYTES = 240 * 1024;

    private NewsDedup() { }

    public static String normalizedTitle(String title) {
        // 数字夹带的标点是锚点：把 1.2 归成 12 会把不同事实并成一条。
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Z}\\s]+", "")
            .replaceAll("(?<![0-9])[\\p{P}]|[\\p{P}](?![0-9])", "");
    }

    public static boolean sameNews(String left, String right) {
        if (left.equals(right)) return true;
        if (left.codePointCount(0, left.length()) < 6 || right.codePointCount(0, right.length()) < 6
            || !anchors(left).equals(anchors(right))) return false;
        Set<String> a = trigrams(left), b = trigrams(right);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= 0.88;
    }

    /** 追加一条证据：重复投递由 isRepeat 判定，超限时只丢中间条目以保住首条与最新一条。 */
    public static Merged merge(String storedJson, Map<String, Object> evidence,
        BiPredicate<Map<String, Object>, Map<String, Object>> isRepeat) {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean repeated = false;
        JsonNode stored = JsonUtils.getJsonMapper().readTree(storedJson);
        for (JsonNode node : stored.path("items")) {
            Map<String, Object> existing = new LinkedHashMap<>(JsonUtils.parseMap(JsonUtils.toJsonString(node)));
            if (!repeated && isRepeat.test(existing, evidence)) repeated = true;
            items.add(existing);
        }
        if (!repeated) items.add(evidence);
        boolean truncated = stored.path("evidenceTruncated").asBoolean();
        while (items.size() > MAX_EVIDENCE_ITEMS
            || JsonUtils.toJsonString(items).getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
            if (items.size() <= 2) throw new HeronBusinessException(400, "news_invalid_input", "来源证据过大");
            items.remove(1);
            truncated = true;
        }
        return new Merged(JsonUtils.toJsonString(Map.of("items", items, "evidenceTruncated", truncated)), repeated);
    }

    /** 合并后的证据列内容与本次是否算重复投递。 */
    public record Merged(String evidence, boolean repeated) { }

    private static Set<String> anchors(String title) {
        return new HashSet<>(NUMBERS.matcher(title).results().map(match -> match.group()).toList());
    }

    private static Set<String> trigrams(String title) {
        int[] points = title.codePoints().toArray();
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= points.length - 3; i++) result.add(new String(points, i, 3));
        return result;
    }
}
