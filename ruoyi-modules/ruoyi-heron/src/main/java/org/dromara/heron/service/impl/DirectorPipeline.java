package org.dromara.heron.service.impl;

import cn.hutool.core.util.EscapeUtil;
import org.dromara.heron.domain.HeronBusinessException;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DirectorPipeline {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "heron-film";

    record Shot(String id, String treatment, String title, String image, long startMs, long endMs,
                boolean interpolated, List<String> techniques) { }

    private DirectorPipeline() { }

    static Path prepare(Path work, ProfileRegistry.Profile profile) {
        Path composition = work.resolve("composition");
        try {
            Map<String, Path> copies = new java.util.HashMap<>();
            for (var asset : profile.files().entrySet()) {
                Path target = composition.resolve(asset.getKey());
                Files.createDirectories(target.getParent());
                Files.copy(asset.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
                copies.put(asset.getKey(), target);
            }
            if (!profile.fingerprint().equals(ProfileRegistry.fingerprint(copies, Map.of()))) {
                throw new HeronBusinessException(409, "news_assets_drifted", "视觉 Profile 在复制期间发生变化：" + profile.id());
            }
            return composition;
        } catch (IOException e) {
            throw new HeronBusinessException(503, "news_director_assets", "导演工作目录无法物化 Profile：" + profile.id(), e);
        }
    }

    static List<Shot> storyboard(JsonNode script, JsonNode dataset, Map<String, String> images,
                                 List<NewsNarrationTiming.Span> timeline, ProfileRegistry.Profile profile) {
        return storyboard(script, dataset, images, timeline, profile, null);
    }

    /**
     * Treatment 驱动版分镜：导演规划的镜头类型、画面目的与技法直接落进 Shot。
     *
     * <p>Treatment 为 null 时回退确定性规则——规划是自由度不是可用性依赖。
     * 旁白窗仍来自实测字幕（导演的 narrationText 只决定镜头意图怎么写进任务卡），
     * 镜头数与实测窗对齐：Treatment 镜头数必须等于 timeline 段数，否则视同规划失败。
     */
    static List<Shot> storyboard(JsonNode script, JsonNode dataset, Map<String, String> images,
                                 List<NewsNarrationTiming.Span> timeline, ProfileRegistry.Profile profile,
                                 TreatmentDirector.Treatment treatment) {
        JsonNode scenes = script.path("scenes");
        if (timeline.size() != scenes.size() + 1 || timeline.getFirst().startMs() != 0
            || ("3d".equals(profile.mode()) && (dataset == null || !"geo".equals(dataset.path("shape").asText())))) {
            throw new HeronBusinessException(409, "news_director_storyboard", "口播镜头未对齐，或三维 Profile 缺少地理快照");
        }
        List<Shot> shots = new ArrayList<>();
        long cursor = 0;
        boolean chartShown = false;
        boolean planned = treatment != null;
        if (planned && treatment.shots().size() != timeline.size()) {
            throw new HeronBusinessException(409, "news_director_storyboard",
                "导演规划镜头数 " + treatment.shots().size() + " 与实测旁白段数 " + timeline.size() + " 不一致");
        }
        for (int i = 0; i < timeline.size(); i++) {
            var span = timeline.get(i);
            if (span.startMs() != cursor || span.endMs() <= cursor) {
                throw new HeronBusinessException(409, "news_director_timing", "分镜时序断裂或为空：" + span.id());
            }
            cursor = span.endMs();
            boolean end = i == scenes.size();
            JsonNode scene = scenes.path(i);
            String image = images.getOrDefault(Integer.toString(i + 1), "");
            if (!image.isEmpty() && !image.matches("scene[0-9]+\\.(png|jpg|jpeg|webp|gif)")) {
                throw new HeronBusinessException(409, "news_director_assets", "非法镜头素材位置：" + span.id());
            }
            String lead = scene.path("on_screen_text").asString("");
            if (lead.isBlank()) {
                lead = scene.path("lead").asString("");
            }
            if (end || lead.isBlank()) {
                lead = script.path("title").asString("");
            }
            String shotTreatment;
            String shotIntent;
            List<String> shotTechniques;
            if (planned) {
                var plannedShot = treatment.shots().get(i);
                shotTreatment = String.valueOf(plannedShot.get("treatment"));
                shotIntent = plannedShot.get("storyPurpose") + " — " + plannedShot.get("visualIntent");
                shotTechniques = plannedShot.get("techniques") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
                if (treatment.vibe()) {
                    shotIntent += "；Vibe 模式：视觉隐喻优先、母题贯穿、设计观众参与瞬间";
                }
            } else {
                shotTreatment = fallbackTreatment(end, image, dataset, i, chartShown);
                if ("chart".equals(shotTreatment)) {
                    chartShown = true;
                }
                shotIntent = lead;
                shotTechniques = List.of();
            }
            shots.add(new Shot(span.id(), shotTreatment, shotIntent, image, span.startMs(), span.endMs(),
                span.cueInterpolated(), shotTechniques));
        }
        return List.copyOf(shots);
    }

    /** 确定性回退的镜头类型判定：Treatment 缺席时沿用原固定规则。 */
    private static String fallbackTreatment(boolean end, String image, JsonNode dataset, int index,
                                             boolean chartShown) {
        if (end || index == 0) {
            return end ? "close" : "hook";
        }
        if (!image.isEmpty()) {
            return "image";
        }
        if (dataset != null && !chartShown) {
            return "chart";
        }
        return index % 2 == 0 ? "statement" : "detail";
    }

    static void compose(Path composition, ProfileRegistry.Profile profile, JsonNode script, JsonNode dataset,
                        List<Shot> shots) {
        compose(composition, profile, script, dataset, shots, null, false);
    }

    static String shotBrief(JsonNode script, Shot shot) {
        JsonNode scenes = script.path("scenes");
        int index = shot.id().startsWith("scene-") ? Integer.parseInt(shot.id().substring(6)) - 1 : scenes.size();
        JsonNode scene = index < scenes.size() ? scenes.get(index) : null;
        String narration = scene == null ? script.path("closing").asString("") :
            scene.path("lead").asString("") + "。" + scene.path("body").asString("");
        String brief = "画面目的：" + shot.title() + "；镜头类型：" + shot.treatment()
            + "；旁白：" + narration + "；上屏主文案不超过 18 字，从旁白里提炼，不要照抄。";
        if (!shot.techniques().isEmpty()) {
            // 整片导演定的技法词汇：镜头导演据此到弹药库挑同类参考卡，不是照抄关键词当动画名
            brief += "；规划技法：" + String.join("、", shot.techniques())
                + "（按技法去弹药库选参考卡，把旁白内容演出来，不是给文案配图）。";
        }
        return brief;
    }

    static void compose(Path composition, ProfileRegistry.Profile profile, JsonNode script, JsonNode dataset,
                        List<Shot> shots, DirectorVisualRework.Patch patch, boolean agentShots) {
        double seconds = shots.getLast().endMs() / 1000.0;
        String source = script.path("source_name").asString("");
        String label = switch (profile.line()) {
            case "data_board" -> "数据图鉴";
            case "trend_insight" -> "趋势调查";
            case "knowledge_explainer" -> "知识图解";
            default -> "新闻解说";
        };
        String title = script.path("title").asString("");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < shots.size(); i++) {
            Shot shot = shots.get(i);
            String treatment = patch != null && patch.shotId().equals(shot.id())
                ? shot.treatment() + " rework-" + patch.action().name() : shot.treatment();
            if (agentShots && Files.isRegularFile(composition.resolve("shots").resolve(shot.id() + ".html"))) {
                content.append("<iframe class=\"clip shot agent-shot\" src=\"shots/").append(shot.id())
                    .append(".html\" data-start=\"").append(sec(shot.startMs()))
                    .append("\" data-duration=\"").append(sec(shot.endMs() - shot.startMs()))
                    .append("\" data-track-index=\"1\"></iframe>\n");
                continue;
            }
            content.append("""
                <section id="%s" class="clip shot %s" data-start="%s" data-duration="%s" data-track-index="1">
                  <div class="field" aria-hidden="true" data-layout-ignore></div>
                  <div class="chapter" aria-hidden="true" data-layout-ignore>%02d</div>
                  <div class="stage">
                    <div class="index-line"><span>%s</span><span>%02d / %02d</span></div>
                    <h1 class="headline%s">%s</h1>
                """.formatted(shot.id(), treatment, sec(shot.startMs()), sec(shot.endMs() - shot.startMs()),
                i + 1, label, i + 1, shots.size(), shot.title().codePointCount(0, shot.title().length()) > 20 ? " long" : "",
                EscapeUtil.escapeHtml4(shot.title())));
            if ("chart".equals(shot.treatment())) {
                content.append("<figure class=\"chart-body\"></figure>\n");
            } else if (!shot.image().isEmpty()) {
                content.append("<figure class=\"image-slot\"><img class=\"photo\" src=\"")
                    .append(shot.image()).append("\" alt=\"\"></figure>\n");
            } else if ("hook".equals(shot.treatment()) && !title.equals(shot.title())) {
                content.append("<p class=\"deck\">").append(EscapeUtil.escapeHtml4(title)).append("</p>\n");
            } else if ("close".equals(shot.treatment()) && !source.isBlank()) {
                content.append("<p class=\"deck\">").append(EscapeUtil.escapeHtml4(source)).append("</p>\n");
            }
            content.append("<div class=\"rule\" aria-hidden=\"true\"></div></div></section>\n");
        }
        String data = dataset == null ? "null" : JSON.writeValueAsString(dataset).replace("<", "\\u003c");
        String html = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8"><title>Heron</title>
            <link rel="stylesheet" href="tokens.css">
            <style>%s</style><script src="vendor/gsap.min.js"></script></head>
            <body><main id="root" class="%s" data-composition-id="heron-film" data-width="%d" data-height="%d" data-duration="%s">
              %s
              <div id="aigc-badge">内容由 AI 生成</div>
              <audio id="narration" class="clip" src="narration.mp3" data-start="0" data-duration="%s" data-track-index="2"></audio>
            </main><script type="application/json" id="dataset">%s</script><script>%s</script></body></html>
            """.formatted(asset("director.css"), profile.id(), profile.width(), profile.height(), sec(shots.getLast().endMs()),
            content, sec(shots.getLast().endMs()), data, asset("director.js"));
        html = html.replace("<main id=\"root\"", "<main id=\"root\" style=\"--accent:" + profile.accent()
            + ";--subtitle-top:" + profile.subtitleTop() + "px\"");
        try {
            Files.writeString(composition.resolve("index.html"), html);
            Files.writeString(composition.resolve("index.motion.json"), JSON.writeValueAsString(Map.of(
                "duration", seconds, "assertions", List.of(
                    Map.of("kind", "appearsBy", "selector", "#aigc-badge", "bySec", 0.2),
                    Map.of("kind", "staysInFrame", "selector", "#aigc-badge")))));
            if (dataset != null) {
                Files.writeString(composition.resolve("dataset.json"), data);
            }
            Files.writeString(composition.resolve("storyboard.json"), JSON.writeValueAsString(Map.of(
                "schemaVersion", "director-storyboard/1", "compositionId", ID,
                "profileId", profile.id(), "profileVersion", profile.version(), "timingSource", "tts-cues", "shots", shots)));
        } catch (IOException e) {
            throw new HeronBusinessException(503, "news_director_assets", "导演产物无法写入", e);
        }
    }

    private static String sec(long ms) {
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
    }

    private static String asset(String name) {
        try {
            return new ClassPathResource("director/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HeronBusinessException(503, "news_director_assets", "导演运行资源无法读取：" + name, e);
        }
    }
}
