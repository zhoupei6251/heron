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
        "(?i)(fetch\\(|XMLHttpRequest|import\\s|require\\(|<script\\s+src|data-composition-src|document\\.write)");

    private final Path shotDir;
    private final JsonNode dataset;
    private final Map<String, Object> profile;
    private final String profileTokens;
    private final String narrative;
    private final ResearchBudget budget;
    private final String shotId;

    public String shotId() {
        return shotId;
    }

    public ShotRenderTools(Path shotDir, JsonNode dataset, Map<String, Object> profile,
                           String profileTokens, String narrative, String shotId, ResearchBudget budget) {
        this.shotDir = shotDir;
        this.dataset = dataset;
        this.profile = profile;
        this.profileTokens = profileTokens;
        this.narrative = narrative;
        this.shotId = shotId;
        this.budget = budget;
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

    @Tool(name = "write_shot_html", description = "写入当前镜头的 HTML 代码（head+body 全量，自包含；引用 tokens.css 与 vendor/gsap.min.js 用相对路径）")
    public Map<String, Object> writeShotHtml(
        @ToolParam(name = "html", description = "完整 HTML 文档；所有数字必须来自 read_dataset", required = true) String html) {
        acquire();
        if (html == null || html.length() < 200 || html.length() > MAX_HTML_CHARS
            || !html.contains("data-composition-id") || BANNED.matcher(html).find()) {
            throw new HeronBusinessException(400, "shot_html_invalid",
                "HTML 缺少 data-composition-id、长度越界或含被禁语法（网络/外部脚本/子组合）");
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
