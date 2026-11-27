package org.dromara.heron.agentscope;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 单镜创作 Agent 的受控工具面：只读冻结素材与 Profile 纪律，只写当前镜头的 HTML。
 *
 * <p>渲染与检查不在这里：Agent 写完代码由 Java 执行渲染和闸门，
 * 报告通过 next-round 的 goal 回灌，Agent 看不到服务器其它任何文件。
 */
public class ShotRenderTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TEXT_CHARS = 40_000;
    private static final int MAX_HTML_CHARS = 60_000;
    private static final Pattern BANNED = Pattern.compile(
        "(?i)(fetch\\(|XMLHttpRequest|import\\s|require\\(|data-composition-src|document\\.write)");
    /** 参考卡 frontmatter 字段行：`key: value`，frontmatter 由 `---` 围栏包裹。 */
    private static final Pattern CARD_FIELD = Pattern.compile("(?m)^([\\w\\u4e00-\\u9fa5]+):\\s*(.+)$");
    private static final int MAX_CARD_CHARS = 16_000;
    /** 弹药库里的说明文件：有标题行但不是参照卡，不进索引。 */
    private static final java.util.Set<String> NON_CARDS = java.util.Set.of("ATTRIBUTION.md", "aesthetic-rules.md");

    private final Path shotDir;
    private final JsonNode dataset;
    private final Map<String, Object> profile;
    private final String profileTokens;
    private final String narrative;
    private final ResearchBudget budget;
    private final String shotId;
    private final Path referenceRoot;

    public String shotId() {
        return shotId;
    }

    public ShotRenderTools(Path shotDir, JsonNode dataset, Map<String, Object> profile,
                           String profileTokens, String narrative, String shotId, ResearchBudget budget) {
        this(shotDir, dataset, profile, profileTokens, narrative, shotId, budget, null);
    }

    public ShotRenderTools(Path shotDir, JsonNode dataset, Map<String, Object> profile,
                           String profileTokens, String narrative, String shotId, ResearchBudget budget,
                           Path creativeKnowledgeRoot) {
        this.shotDir = shotDir;
        this.dataset = dataset;
        this.profile = profile;
        this.profileTokens = profileTokens;
        this.narrative = narrative;
        this.shotId = shotId;
        this.budget = budget;
        // 弹药库：reference-cards 下的镜头配方/风格配方/官方组件，参照资产不是可渲染模板
        this.referenceRoot = creativeKnowledgeRoot == null ? null
            : creativeKnowledgeRoot.resolve("reference-cards").toAbsolutePath().normalize();
    }

    @Tool(name = "read_shot_brief", description = "读取当前镜头的任务卡：画面目的、旁白、时长、镜头语言与 Profile 视觉纪律", readOnly = true)
    public Map<String, Object> readShotBrief() {
        acquire();
        return Map.of("shotId", shotId, "narrative", narrative, "profile", profile,
            "tokensCss", profileTokens, "datasetAvailable", dataset != null);
    }

    @Tool(name = "read_dataset", description = "读取本片冻结数据快照（图表与数字的唯一真值，禁止自编数字）", readOnly = true)
    public String readDataset() {
        acquire();
        if (dataset == null) {
            return "本片没有数据快照，不要在画面上呈现图表或数值";
        }
        return JSON.writeValueAsString(dataset);
    }

    @Tool(name = "write_shot_html", description = "写入当前镜头的 HTML 代码（完整文档；只允许引用本地 tokens.css 与 vendor/gsap.min.js）")
    public Map<String, Object> writeShotHtml(
        @ToolParam(name = "html", description = "完整 HTML 文档；所有数字必须来自 read_dataset", required = true) String html) {
        acquire();
        if (html == null || html.length() < 200 || html.length() > MAX_HTML_CHARS
            || !html.contains("data-composition-id") || BANNED.matcher(html).find()
            || !html.contains("vendor/gsap.min.js") || !html.contains("tokens.css")) {
            throw new HeronBusinessException(400, "shot_html_invalid",
                "HTML 缺少 data-composition-id 或本地 tokens.css/gsap 引用、长度越界或含被禁语法（网络/子组合）");
        }
        try {
            Path file = shotDir.resolve(shotId + ".html");
            Files.writeString(file, html, StandardCharsets.UTF_8);
            return Map.of("written", file.getFileName().toString(), "chars", html.length());
        } catch (IOException e) {
            throw new HeronBusinessException(503, "shot_write_failed", "镜头 HTML 写入失败", e);
        }
    }

    @Tool(name = "read_rework_notes", description = "读取上一轮渲染与检查失败的具体报告，返工前必读", readOnly = true)
    public List<String> readReworkNotes() {
        acquire();
        Path notes = shotDir.resolve(shotId + ".rework.json");
        if (!Files.isRegularFile(notes)) {
            return List.of("没有返工记录，这是第一轮创作");
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(notes, StandardCharsets.UTF_8));
            List<String> out = new java.util.ArrayList<>();
            for (JsonNode item : node.path("notes")) {
                out.add(item.asString(""));
            }
            return out.isEmpty() ? List.of(node.toString()) : List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return List.of("返工记录不可读，按检查失败从头创作");
        }
    }

    /**
     * 弹药库索引：镜头配方（camera/effects/opening…十二类）与画派风格配方、解说语法。
     * 索引现算 frontmatter / 标题行，无预生成清单——卡片增删即刻生效，不存在两份真源漂移。
     * 只收 <库>/<集>/<卡>.md 三级路径下的卡；散在库根的 ATTRIBUTION/LICENSE 等说明文件不进索引。
     */
    @Tool(name = "list_reference_cards", description = "列出弹药库参考卡索引：镜头配方（camera/effects/opening 等类别）与画派风格、解说语法，每张带一句话摘要。先查此表再选要精读的卡", readOnly = true)
    public Map<String, Object> listReferenceCards() {
        acquire();
        if (referenceRoot == null || !Files.isDirectory(referenceRoot)) {
            return Map.of("count", 0, "cards", Map.of(), "note", "弹药库未配置，直接按任务卡创作");
        }
        Map<String, List<Map<String, String>>> cards = new java.util.TreeMap<>();
        try (var walk = Files.walk(referenceRoot, 3)) {
            for (Path file : walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                Path relative = referenceRoot.relativize(file);
                // 卡必须是 <集>/<卡>.md 两级以上且不在说明文件黑名单里；LICENSE/ATTRIBUTION/审美准则不是参照卡
                if (relative.getNameCount() < 2 || NON_CARDS.contains(file.getFileName().toString())) {
                    continue;
                }
                Map<String, String> summary = cardSummary(file, relative);
                if (summary == null) {
                    continue;
                }
                String group = relative.getParent().toString().replace('\\', '/');
                cards.computeIfAbsent(group, key -> new java.util.ArrayList<>()).add(summary);
            }
        } catch (IOException e) {
            throw new HeronBusinessException(503, "reference_cards_unavailable", "弹药库参考卡无法扫描", e);
        }
        int count = cards.values().stream().mapToInt(List::size).sum();
        return Map.of("count", count, "cards", cards);
    }

    @Tool(name = "read_reference_card", description = "精读一张参考卡全文（意图、动作阶段、参数表、已知坑）。cardId 必须来自 list_reference_cards", readOnly = true)
    public String readReferenceCard(
        @ToolParam(name = "cardId", description = "list_reference_cards 返回的 id（如 camera/crash-zoom-punch）", required = true) String cardId) {
        acquire();
        Path file = resolveCard(cardId);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.length() <= MAX_CARD_CHARS ? text : text.substring(0, MAX_CARD_CHARS) + "\n…[已截断]";
        } catch (IOException e) {
            throw new HeronBusinessException(503, "reference_card_unreadable", "参考卡无法读取：" + cardId, e);
        }
    }

    /** 卡片路径只允许库内一~三级目录，拒绝越界引用。 */
    private Path resolveCard(String cardId) {
        if (referenceRoot == null || cardId == null
            || !cardId.matches("[\\w\\u4e00-\\u9fa5-]{1,80}(/[\\w\\u4e00-\\u9fa5-]{1,80}){1,2}")) {
            throw new HeronBusinessException(400, "reference_card_invalid", "参考卡 id 不合法：" + cardId);
        }
        Path file = referenceRoot.resolve(cardId + ".md").toAbsolutePath().normalize();
        if (!file.startsWith(referenceRoot) || !Files.isRegularFile(file)) {
            throw new HeronBusinessException(404, "reference_card_missing", "参考卡不存在：" + cardId);
        }
        return file;
    }

    /**
     * 卡片摘要：优先 frontmatter（一句话/适用/时长/能量），无 frontmatter 的画派风格卡回退首行标题。
     * 两种格式都进索引——frontmatter 是 shotcraft 的约定，标题行是 huashu 的约定，不强行统一卡片格式。
     */
    private Map<String, String> cardSummary(Path file, Path relative) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("id", relative.getParent().toString().replace('\\', '/') + "/"
                + file.getFileName().toString().replaceFirst("\\.md$", ""));
            String[] parts = text.split("---");
            if (parts.length >= 3 && parts[0].isBlank()) {
                Map<String, String> fields = new LinkedHashMap<>();
                java.util.regex.Matcher matcher = CARD_FIELD.matcher(parts[1]);
                while (matcher.find()) {
                    fields.putIfAbsent(matcher.group(1), matcher.group(2).trim());
                }
                if (!fields.containsKey("一句话")) {
                    return null;
                }
                fields.forEach((key, value) -> {
                    if (List.of("一句话", "适用", "时长", "能量").contains(key)) {
                        summary.put(key, value);
                    }
                });
                return summary;
            }
            String firstLine = text.lines().findFirst().orElse("");
            if (firstLine.startsWith("# ") && firstLine.length() > 3) {
                summary.put("一句话", firstLine.substring(2).strip().replaceFirst("^\\S+\\s*·\\s*", ""));
                return summary;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private void acquire() {
        if (budget == null) {
            return;
        }
        String rejection = budget.tryAcquireTool();
        if (rejection != null) {
            throw new HeronBusinessException(429, rejection, "镜头创作预算已用尽，请输出当前最佳版本");
        }
    }
}
