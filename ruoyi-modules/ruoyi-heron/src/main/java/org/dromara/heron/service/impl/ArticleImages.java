package org.dromara.heron.service.impl;

import org.dromara.common.json.utils.JsonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从抓到的正文 HTML 里抽图片，作为解说线的画面素材。
 *
 * <p>素材来源只有一条：**原文自带的图**。不接图库、不生成配图——事发照片只能是事发媒体的照片，
 * 配一张「类似的」图会变成假现场。所以这里只做「原文有就抽出来，没有就没有」。
 *
 * <p>抽出来的每个资产带来源 URL 与所在文章 URL，审稿与审计都能回答「这张图哪来的」。
 */
public final class ArticleImages {

    /** img 标签宽松匹配：正文来自各家站点，不能指望格式统一。 */
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("(?:^|\\s)(?:data-src|data-original|src)\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_IMAGES = 8;
    /** 这些通常是图标、logo、追踪像素，不是画面素材。 */
    private static final List<String> SKIP_HINTS = List.of(
        "logo", "icon", "avatar", "sprite", "pixel", "spacer", "blank.gif", "1x1", "qrcode", "emoji");

    private ArticleImages() { }

    /**
     * 抽取可用图片，按出现顺序去重。
     *
     * @param html      正文 HTML
     * @param articleUrl 正文所在页面，用于相对地址解析与来源留痕
     */
    public static ArrayNode extract(String html, String articleUrl) {
        ArrayNode assets = JsonNodeFactory.instance.arrayNode();
        if (html == null || html.isBlank()) {
            return assets;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher tags = IMG.matcher(html);
        while (tags.find() && assets.size() < MAX_IMAGES) {
            Matcher src = SRC.matcher(tags.group());
            if (!src.find()) {
                continue;
            }
            String absolute = absolute(src.group(1), articleUrl);
            if (absolute == null || !usable(absolute) || !seen.add(absolute)) {
                continue;
            }
            ObjectNode asset = assets.addObject();
            // 编号按已收集数量算：addObject 已经加进去了，用 size() 会从 1 开始
            asset.put("asset_id", "img_" + (assets.size() - 1));
            asset.put("source_url", absolute);
            asset.put("article_url", articleUrl == null ? "" : articleUrl);
        }
        return assets;
    }

    private static String absolute(String raw, String articleUrl) {
        String value = raw.trim();
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (articleUrl == null || articleUrl.isBlank()) {
            return null;
        }
        try {
            return URI.create(articleUrl).resolve(value).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean usable(String url) {
        String lower = url.toLowerCase();
        if (!lower.startsWith("http")) {
            return false;
        }
        // data: 占位图与 svg 图标都不是画面素材
        if (lower.contains(".svg")) {
            return false;
        }
        return SKIP_HINTS.stream().noneMatch(lower::contains);
    }

    /** 把资产清单折成提示词里的一行说明，供写稿时决定哪一段用图。 */
    public static String promptHint(ArrayNode assets) {
        if (assets == null || assets.isEmpty()) {
            return "（原文没有可用图片，全部场景用文字表达）";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode asset : assets) {
            lines.add("- " + asset.path("asset_id").asString("") + " 来自 " + asset.path("source_url").asString(""));
        }
        return "原文可用图片（scene_type=image 时从这些 asset_id 里选，不要自造）：\n" + String.join("\n", lines);
    }
}
