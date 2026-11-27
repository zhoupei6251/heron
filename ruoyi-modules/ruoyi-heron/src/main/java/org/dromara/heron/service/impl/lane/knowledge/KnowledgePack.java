package org.dromara.heron.service.impl.lane.knowledge;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个知识源在固定 commit 上的读取口径：manifest + entries.json + LICENSE。
 *
 * <p>知识包是 git 里的静态文件，不是活的网络源：按 sourceKey 目录读取，
 * entryId 由导入脚本稳定生成，引用因此能精确到书的某个版本。
 * 包缺失或条目对不上直接拒，不猜、不降级成「随便写点」。
 */
public final class KnowledgePack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SOURCE_KEY = "[a-z0-9-]{1,40}";

    private final String sourceKey;
    private final JsonNode manifest;
    private final Map<String, Entry> entries;
    private final String digest;

    /** 一条结构化建议：说人话、原文、来源与证据等级。 */
    public record Entry(String entryId, String chapterTitle, String title, String plain,
                        String benefit, String cost, String evidenceLevel, String sourceText,
                        List<String> sourceUrls) { }

    private KnowledgePack(String sourceKey, JsonNode manifest, Map<String, Entry> entries, String digest) {
        this.sourceKey = sourceKey;
        this.manifest = manifest;
        this.entries = entries;
        this.digest = digest;
    }

    /**
     * 从知识包根目录读取一个源：manifest、entries 与 LICENSE 三件齐全才算可用。
     *
     * @param root      知识包根目录（配置项 knowledgeDir）
     * @param sourceKey 知识源 key，只允许小写字母、数字与连字符，最长 40
     * @return 只读知识包
     * @throws HeronBusinessException 409 {@code knowledge_pack_missing}，key 非法、文件缺失、
     *                                JSON 不可读或条目 ID 为空/重复
     */
    public static KnowledgePack load(Path root, String sourceKey) {
        if (sourceKey == null || !sourceKey.matches(SOURCE_KEY)) {
            throw missing("知识源 key 不合法：" + sourceKey);
        }
        Path dir = root.resolve(sourceKey);
        Path manifestFile = dir.resolve("manifest.json");
        Path entriesFile = dir.resolve("entries.json");
        Path licenseFile = dir.resolve("LICENSE");
        if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(entriesFile)
            || !Files.isRegularFile(licenseFile)) {
            throw missing("知识包不完整：" + sourceKey + " 缺少 manifest/entries/LICENSE");
        }
        try {
            JsonNode manifest = JSON.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
            byte[] raw = Files.readAllBytes(entriesFile);
            JsonNode list = JSON.readTree(new String(raw, StandardCharsets.UTF_8)).path("entries");
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (JsonNode item : list) {
                Entry entry = new Entry(
                    item.path("entryId").asString(""),
                    item.path("chapterTitle").asString(""),
                    item.path("title").asString(""),
                    item.path("plain").asString(""),
                    item.path("benefit").asString(""),
                    item.path("cost").asString(""),
                    item.path("evidenceLevel").asString(""),
                    item.path("sourceText").asString(""),
                    urls(item.path("sourceUrls")));
                if (entry.entryId().isBlank() || entries.put(entry.entryId(), entry) != null) {
                    throw missing("知识包条目 ID 为空或重复：" + entry.entryId());
                }
            }
            return new KnowledgePack(sourceKey, manifest, entries, DigestUtil.sha256Hex(raw));
        } catch (IOException | tools.jackson.core.JacksonException e) {
            throw missing("知识包不可读：" + sourceKey);
        }
    }

    private static List<String> urls(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String url = item.asString("");
            if (!url.isBlank()) {
                out.add(url);
            }
        }
        return List.copyOf(out);
    }

    /** 注册表里的知识源 key。 */
    public String sourceKey() {
        return sourceKey;
    }

    /** manifest 里的书名/标题。 */
    public String title() {
        return manifest.path("title").asString("");
    }

    /** 上游仓库地址。 */
    public String originUrl() {
        return manifest.path("originUrl").asString("");
    }

    /** 锁定的上游 commit，引用据此可复核。 */
    public String pinnedCommit() {
        return manifest.path("pinnedCommit").asString("");
    }

    /** 锁定 commit 的日期。 */
    public String pinnedDate() {
        return manifest.path("pinnedDate").asString("");
    }

    /** 许可证代码。 */
    public String license() {
        return manifest.path("license").asString("");
    }

    /** 许可证链接；CC BY 类许可发布时必须带出。 */
    public String licenseUrl() {
        return manifest.path("licenseUrl").asString("");
    }

    /** 要求的署名文本。 */
    public String attribution() {
        return manifest.path("attribution").asString("");
    }

    /** 条目文件实算哈希：冻结稿件的 body_sha256 就是它，换版本必然换值。 */
    public String digest() {
        return digest;
    }

    /** 按选题时定下的 entryId 取条目；任何一条对不上都拒，不拿相近的条目顶替。 */
    public List<Entry> require(List<String> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            throw missing("知识解释线没有选定条目");
        }
        List<Entry> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String id : entryIds) {
            Entry entry = entries.get(id);
            if (entry == null) {
                unknown.add(id);
            } else {
                out.add(entry);
            }
        }
        if (!unknown.isEmpty()) {
            throw missing("知识包 " + sourceKey + " 里没有这些条目：" + String.join("、", unknown));
        }
        return out;
    }

    /** 审稿依据：给模型看的就是这几条原文，不给全书，免得它自己另挑一条。 */
    public String evidenceJson(List<Entry> selected) {
        ArrayNode array = JSON.createArrayNode();
        for (Entry entry : selected) {
            ObjectNode node = array.addObject();
            node.put("entryId", entry.entryId());
            node.put("chapterTitle", entry.chapterTitle());
            node.put("title", entry.title());
            node.put("plain", entry.plain());
            node.put("benefit", entry.benefit());
            node.put("cost", entry.cost());
            node.put("evidenceLevel", entry.evidenceLevel());
            node.put("sourceText", entry.sourceText());
        }
        return array.toString();
    }

    /**
     * 引用清单：脚本每段引了哪些条目、分别出现在第几屏，以及许可与署名。
     * 产物不是审计装饰：CC BY 4.0 要求署名、许可链接与「有修改」声明，缺一项就不能发布。
     */
    public String citationsJson(Map<Integer, List<String>> sceneRefs) {
        Set<String> ids = new LinkedHashSet<>();
        sceneRefs.values().forEach(ids::addAll);
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("sourceKey", sourceKey);
        root.put("title", title());
        root.put("originUrl", originUrl());
        root.put("pinnedCommit", pinnedCommit());
        root.put("pinnedDate", pinnedDate());
        root.put("license", license());
        root.put("licenseUrl", licenseUrl());
        root.put("attribution", attribution());
        ArrayNode list = root.putArray("entries");
        for (String id : ids) {
            Entry entry = entries.get(id);
            if (entry == null) {
                throw missing("引用清单里出现未知条目：" + id);
            }
            ObjectNode node = list.addObject();
            node.put("entryId", id);
            node.put("chapterTitle", entry.chapterTitle());
            node.put("title", entry.title());
            ArrayNode scenes = node.putArray("citedInScenes");
            for (Map.Entry<Integer, List<String>> scene : sceneRefs.entrySet()) {
                if (scene.getValue().contains(id)) {
                    scenes.add(scene.getKey());
                }
            }
            ArrayNode urls = node.putArray("sourceUrls");
            entry.sourceUrls().forEach(urls::add);
        }
        return root.toString();
    }

    /** 按 entryId 取单条；不存在时返回 null（调用方应先用 {@link #require} 断言）。 */
    public Entry entry(String entryId) {
        return entries.get(entryId);
    }

    /** 全部条目，按 entries.json 的顺序；选题按窗口轮换读取。 */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** 条目里用于「连续相同字数」判定的原文：说人话与来源摘录都算。 */
    public String verifiableText(Entry entry) {
        return entry.plain() + "\n" + entry.sourceText();
    }

    private static HeronBusinessException missing(String reason) {
        return new HeronBusinessException(409, "knowledge_pack_missing", reason);
    }
}
