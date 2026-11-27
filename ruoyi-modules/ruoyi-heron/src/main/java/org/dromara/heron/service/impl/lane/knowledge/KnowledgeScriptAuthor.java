package org.dromara.heron.service.impl.lane.knowledge;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.impl.lane.news.NewsScriptContract;
import org.dromara.heron.service.impl.shared.support.ModelJson;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识解释线写稿：素材来自固定版本的知识包，不是抓到的正文。
 *
 * <p>出处三字段由知识包覆盖，模型自述不采信：source_url 取 manifest.originUrl，
 * fetched_at 取 pinnedDate，body_sha256 取 entries.json 的实算哈希。
 * 每段的 entry_ids 必须是选题时定下的那几条，citation 闸会逐段核对。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeScriptAuthor implements NewsScriptAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/knowledge-explainer/writing-style.md")
        + "\n\n---\n\n" + resource("/prompt/news-explainer/script-contract.md");

    private final OpenAiModelClient model;
    private final HeronProductionProperties properties;

    @Override
    public String author(ProductionRunVo run) {
        JsonNode input = JSON.readTree(run.inputJson());
        KnowledgePack pack = pack(input);
        List<KnowledgePack.Entry> selected = pack.require(entryIds(input));
        ObjectNode script = withEvidence(parse(model.chat(run, "script", SYSTEM, prompt(input, pack, selected))), pack);
        List<String> violations = NewsScriptContract.violations(script);
        if (!violations.isEmpty()) {
            ObjectNode repaired = withEvidence(
                parse(model.chat(run, "script-repair", SYSTEM, repairPrompt(script, violations))), pack);
            violations = NewsScriptContract.violations(repaired);
            if (!violations.isEmpty()) {
                throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：" + String.join("；", violations));
            }
            script = repaired;
        }
        return JSON.writeValueAsString(script);
    }

    /** 审稿判 BLOCKER/MAJOR 后的定向修复：素材不变，只改被点出的问题。 */
    @Override
    public String repair(ProductionRunVo run, String scriptJson, List<String> notes) {
        JsonNode input = JSON.readTree(run.inputJson());
        KnowledgePack pack = pack(input);
        List<KnowledgePack.Entry> selected = pack.require(entryIds(input));
        ObjectNode script = withEvidence(parse(model.chat(run, "script-repair", SYSTEM,
            reviewRepairPrompt(input, pack, selected, scriptJson, notes))), pack);
        List<String> violations = NewsScriptContract.violations(script);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "news_script_contract", "修复稿仍未过契约：" + String.join("；", violations));
        }
        return JSON.writeValueAsString(script);
    }

    /** 与其他线一致：按 sourceKey 从 data/knowledge 下取包；缺包直接 409。 */
    private KnowledgePack pack(JsonNode input) {
        return KnowledgePack.load(properties.knowledgeDir(),
            input.path("selection").path("dataSourceKey").asString(""));
    }

    private static List<String> entryIds(JsonNode input) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode id : input.path("selection").path("sourceEvidenceIds")) {
            ids.add(id.asString(""));
        }
        return ids;
    }

    /** 出处事实由知识包覆盖，模型自述一律不采信。 */
    private static ObjectNode withEvidence(ObjectNode script, KnowledgePack pack) {
        script.put("source_url", pack.originUrl());
        script.put("fetched_at", pack.pinnedDate());
        script.put("body_sha256", pack.digest());
        script.put("source_name", pack.title());
        script.put("schema_version", "1");
        return script;
    }

    static String prompt(JsonNode input, KnowledgePack pack, List<KnowledgePack.Entry> selected) {
        StringBuilder text = new StringBuilder()
            .append("标题：").append(input.path("title").asString("")).append('\n')
            .append("草稿：").append(input.path("draft").asString("")).append('\n');
        appendEditorial(text, input.path("editorial"));
        text.append("素材（只能用这几条，按执行顺序自己排，3–5 条即可）：\n");
        for (KnowledgePack.Entry entry : selected) {
            text.append("\n[").append(entry.entryId()).append("] ")
                .append(entry.chapterTitle()).append(" · ").append(entry.title()).append('\n')
                .append("说人话：").append(entry.plain()).append('\n');
            if (!entry.benefit().isBlank()) {
                text.append("收益：").append(entry.benefit()).append('\n');
            }
            if (!entry.cost().isBlank()) {
                text.append("花费：").append(entry.cost()).append('\n');
            }
            if (!entry.evidenceLevel().isBlank()) {
                text.append("证据等级：").append(entry.evidenceLevel()).append('\n');
            }
            if (!entry.sourceText().isBlank()) {
                text.append("原文：").append(entry.sourceText()).append('\n');
            }
        }
        text.append("\n署名：closing 必须以这行原文结尾，一个字都不能改：\n")
            .append(pack.attribution()).append('\n')
            .append("许可：").append(pack.license()).append("（").append(pack.licenseUrl()).append("）\n")
            .append("每段必须带 entry_ids，只能使用上面列出的 entryId；不要写 scene_type 与 visual，保持纯文字。\n")
            .append("按契约输出完整稿件 JSON。");
        return text.toString();
    }

    private static String repairPrompt(ObjectNode script, List<String> violations) {
        return "上一版稿件没有通过契约校验，问题如下：\n- " + String.join("\n- ", violations)
            + "\n只修改不符合要求的字段，其余字段原样保留，输出完整 JSON。\n上一版稿件：\n" + script;
    }

    private static String reviewRepairPrompt(JsonNode input, KnowledgePack pack,
                                             List<KnowledgePack.Entry> selected, String scriptJson,
                                             List<String> notes) {
        return "上一版稿件没有通过审稿，问题如下：\n- " + String.join("\n- ", notes)
            + "\n只修改被点出的问题，其余字段与数字原样保留，输出完整 JSON。\n"
            + "素材与署名要求不变：\n" + prompt(input, pack, selected) + "\n上一版稿件：\n" + scriptJson;
    }

    private static void appendEditorial(StringBuilder text, JsonNode editorial) {
        String intent = editorial.path("editorialIntent").asString("").strip();
        String reason = editorial.path("reason").asString("").strip();
        if (intent.isBlank() && reason.isBlank()) {
            return;
        }
        text.append("编辑意图：").append(intent.isBlank() ? "（未给出）" : intent).append('\n');
        if (!reason.isBlank()) {
            text.append("为什么选它：").append(reason).append('\n');
        }
    }

    private static ObjectNode parse(String text) {
        JsonNode node = text == null ? null : JSON.readTree(ModelJson.unwrap(text));
        if (node == null || !node.isObject()) {
            throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：模型输出不是 JSON 对象");
        }
        return (ObjectNode) node;
    }

    private static String resource(String path) {
        try (InputStream stream = KnowledgeScriptAuthor.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
