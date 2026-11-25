package org.dromara.heron.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 配乐曲库：目录旁边一份清单，标签与权重都在清单里。
 *
 * <p>为什么要有这一层：配乐过去按「今天第几天」轮换 `data/audio/bgm/*.mp3`，
 * 每首曲目都是一样的概率、一样的用途。仓库里的曲目听感差很远，有些适合解释型、
 * 有些只适合悬念，轮换等于每三期就撞一次不搭的配乐。清单让「这首给什么线用」
 * 变成可审计的数据，而不是文件名里猜出来的东西。
 *
 * <p>选曲不是让模型直接点名：模型影响的是故事计划里已经冻结的 {@code emotion}/{@code rhythm}，
 * 这里按编辑线过滤、按情绪节奏加权、再按权重摇号。<b>同样的输入仍然选出同一首</b>——
 * 摇号的种子来自冻结输入的哈希，不做真随机，否则同一个任务重跑会换配乐，
 * 而成片指纹、审稿与返工都对不上。
 */
final class BgmCatalog {

    private static final String CATALOG_FILE = "catalog.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 权重上限：避免一份手写清单把某一首写成永久唯一解。 */
    private static final int MAX_WEIGHT = 100;

    private BgmCatalog() { }

    /**
     * 一首配乐。
     *
     * @param key      稳定标识，进闸报告
     * @param file     文件名，相对配乐目录
     * @param weight   权重，1~100
     * @param lines    适用的编辑线；空表示不限
     * @param emotions 适用的故事情绪；空表示不限
     * @param rhythms  适用的叙事节奏；空表示不限
     */
    record Track(String key, String file, int weight, Set<String> lines,
                 Set<String> emotions, Set<String> rhythms) {

        boolean accepts(String line) {
            return lines.isEmpty() || lines.contains(line);
        }

        /** 配乐与这一段故事的气质是否合得来；情绪与节奏各算一次命中。 */
        int affinity(String emotion, String rhythm) {
            int hits = 0;
            if (emotion != null && !emotion.isBlank() && emotions.contains(emotion)) {
                hits++;
            }
            if (rhythm != null && !rhythm.isBlank() && rhythms.contains(rhythm)) {
                hits++;
            }
            return hits;
        }
    }

    /**
     * 一次选曲的结果。
     *
     * @param track    选中的曲目；{@code null} 表示曲库不可用
     * @param file     选中曲目的绝对路径；与 {@code track} 同时为空或同时非空
     * @param reason   为什么是这首，写进闸报告给人看
     * @param measured 候选与权重，供复核
     */
    record Pick(Track track, Path file, String reason, Map<String, Object> measured) { }

    /**
     * 读清单。清单不存在或不可解析时返回空列表，由调用方退化成无配乐——
     * 一份写坏的清单不该让整条新闻线停摆。
     */
    static List<Track> load(Path bgmDir) {
        Path catalog = bgmDir.resolve(CATALOG_FILE);
        if (!Files.isRegularFile(catalog)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(Files.readString(catalog, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        JsonNode tracks = root.path("tracks");
        if (!tracks.isArray()) {
            return List.of();
        }
        List<Track> out = new ArrayList<>();
        for (JsonNode item : tracks) {
            String key = item.path("key").asString("");
            String file = item.path("file").asString("");
            // 文件名只认配乐目录里的普通 mp3：清单是数据，不能靠它指到目录外
            if (key.isBlank() || file.isBlank() || file.contains("/") || file.contains("\\")
                || !file.toLowerCase(Locale.ROOT).endsWith(".mp3")
                || !Files.isRegularFile(bgmDir.resolve(file))) {
                continue;
            }
            int weight = item.path("weight").asInt(10);
            out.add(new Track(key, file, Math.max(1, Math.min(MAX_WEIGHT, weight)),
                strings(item.path("lines")), strings(item.path("emotions")), strings(item.path("rhythms"))));
        }
        return out;
    }

    /**
     * 选一首。
     *
     * <p>三级过滤：编辑线不符的直接出局；剩下的按情绪/节奏算亲和度；
     * 最后在「最高亲和度那一档」里按权重摇号。取最高档而不做全体加权，
     * 是因为情绪对不上却因为权重高被选中，等于把标签写成了摆设。
     *
     * <p>种子取自冻结输入哈希，同一任务的同一份输入永远选到同一首。
     *
     * @param tracks 曲库
     * @param line   编辑线，如 {@code DATA_BOARD}；空表示不按线过滤
     * @param emotion 故事计划的情绪，可为空
     * @param rhythm  故事计划的节奏，可为空
     * @param seed    决定摇号结果的稳定种子
     * @param dir     曲库目录，用于把选中的文件名解析成绝对路径
     */
    static Pick pick(List<Track> tracks, Path dir, String line, String emotion, String rhythm, String seed) {
        if (tracks == null || tracks.isEmpty()) {
            return new Pick(null, null, "配乐曲库不可用，本期无配乐", new TreeMap<>());
        }
        List<Track> eligible = tracks.stream()
            .filter(track -> line == null || line.isBlank() || track.accepts(line))
            .toList();
        if (eligible.isEmpty()) {
            return new Pick(null, null, "曲库里没有适用于编辑线 " + line + " 的配乐，本期无配乐", new TreeMap<>());
        }
        int best = eligible.stream().mapToInt(track -> track.affinity(emotion, rhythm)).max().orElse(0);
        List<Track> tier = eligible.stream()
            .filter(track -> track.affinity(emotion, rhythm) == best)
            .toList();
        int total = tier.stream().mapToInt(Track::weight).sum();
        int ticket = Math.floorMod(seed == null ? 0 : seed.hashCode(), total);
        Track chosen = tier.get(tier.size() - 1);
        int cursor = 0;
        for (Track track : tier) {
            cursor += track.weight;
            if (ticket < cursor) {
                chosen = track;
                break;
            }
        }
        Map<String, Object> measured = new TreeMap<>();
        measured.put("line", line);
        measured.put("emotion", emotion);
        measured.put("rhythm", rhythm);
        measured.put("candidates", eligible.size());
        measured.put("tier", tier.size());
        measured.put("affinity", best);
        measured.put("totalWeight", total);
        measured.put("ticket", ticket);
        measured.put("chosenWeight", chosen.weight());
        String reason = "按编辑线 " + (line == null || line.isBlank() ? "不限" : line)
            + " 过滤，" + (best == 0 ? "情绪节奏没有命中，按权重摇号" : "情绪节奏命中 " + best + " 项")
            + "，权重 " + chosen.weight() + " 中签";
        return new Pick(chosen, dir == null ? null : dir.resolve(chosen.file()), reason, measured);
    }

    /**
     * 选曲需要的三件事：编辑线、故事计划首屏的情绪与节奏。
     *
     * <p>编辑线取自模板的 {@code supportedLines}，情绪节奏取自脚本里已冻结的故事计划。
     * 两处都是生产已经定下的值，这里只读、不重新推导。
     */
    record Story(String line, String emotion, String rhythm) { }

    static Story storyOf(JsonNode script, Path templateDir) {
        String line = templateLine(templateDir);
        JsonNode scenes = script == null ? null : script.path("story_plan").path("scenes");
        if (scenes == null || !scenes.isArray() || scenes.isEmpty()) {
            return new Story(line, null, null);
        }
        return new Story(line, scenes.get(0).path("emotion").asString(""),
            scenes.get(0).path("rhythm").asString(""));
    }

    /**
     * 模板声明的编辑线。读不到就返回空串：模板没声明线时不做线过滤，
     * 由情绪节奏与权重决定，而不是直接报错停线。
     */
    static String templateLine(Path templateDir) {
        Path meta = templateDir.resolve("template.json");
        if (!Files.isRegularFile(meta)) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(meta, StandardCharsets.UTF_8));
            JsonNode lines = root.path("supportedLines");
            return lines.isArray() && !lines.isEmpty() ? lines.get(0).asString("") : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        node.forEach(item -> {
            String value = item.asString("");
            if (!value.isBlank()) {
                out.add(value);
            }
        });
        return Set.copyOf(out);
    }

    /**
     * 曲库目录：随模板根走，与配乐过去的位置一致。
     *
     * <p>找不到就返回 {@code null}，由调用方退化成无配乐——目录缺失是部署侧的配置问题，
     * 不该让整条新闻线抛异常停摆，但要在闸报告里留下原因。
     */
    static Path directory(Path templateRoot) {
        if (templateRoot != null) {
            Path parent = templateRoot.toAbsolutePath().normalize().getParent();
            Path audio = parent == null ? null : parent.resolve("audio").resolve("bgm");
            if (audio != null && Files.isDirectory(audio)) {
                return audio;
            }
        }
        Path fallback = Path.of("data", "audio", "bgm").toAbsolutePath().normalize();
        return Files.isDirectory(fallback) ? fallback : null;
    }
}
