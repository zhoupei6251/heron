package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 数据看板脚本由模型写，出处三件（哈希、源、图表类型）由快照覆盖；
 * 契约不过先定向修复一次，再不过就退回，不盲目重跑。
 */
@Service
@RequiredArgsConstructor
public class DatasetScriptAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE_SYSTEM = resource("/prompt/data-board/script-contract.md");
    private static final String STAGE = "dataset-script";
    private static final int MAX_REPAIRS = 2;

    private final OpenAiModelClient model;
    private final DatasetTitleAuthor titleAuthor;

    public String author(ProductionRunVo run, String datasetJson, boolean digest) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        List<String> titles = titleAuthor.candidates(run, datasetJson);
        String system = BASE_SYSTEM + "\n\n---\n\n"
            + ColumnPacks.text(snapshot.path("source_key").asString(""));
        JsonNode draft = withEvidence(parse(model.chat(run, STAGE, system, prompt(snapshot, titles, digest))), snapshot);
        List<String> violations = violations(draft, snapshot, titles);
        for (int repair = 0; repair < MAX_REPAIRS && !violations.isEmpty(); repair++) {
            draft = withEvidence(parse(model.chat(run, STAGE + "-repair", system,
                repairPrompt(draft, violations, titles))), snapshot);
            violations = violations(draft, snapshot, titles);
        }
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_script_contract",
                "脚本退回写稿：" + String.join("；", violations));
        }
        return JSON.writeValueAsString(draft);
    }

    /** 审稿不过时的定向修复：复用同一套修复提示与契约复核，只换掉问题清单。 */
    public String repair(ProductionRunVo run, String datasetJson, String scriptJson, List<String> issues) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        List<String> titles = titleAuthor.candidates(run, datasetJson);
        String system = BASE_SYSTEM + "\n\n---\n\n"
            + ColumnPacks.text(snapshot.path("source_key").asString(""));
        JsonNode repaired = withEvidence(parse(model.chat(run, STAGE + "-repair", system,
            repairPrompt(JSON.readTree(scriptJson), issues, titles))), snapshot);
        List<String> violations = violations(repaired, snapshot, titles);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "dataset_script_contract",
                "脚本退回修复：" + String.join("；", violations));
        }
        return JSON.writeValueAsString(repaired);
    }

    private static List<String> violations(JsonNode script, JsonNode snapshot, List<String> titles) {
        List<String> violations = new java.util.ArrayList<>(DatasetScriptContract.violations(script, snapshot));
        if (!titles.contains(script.path("title").asString(""))) {
            violations.add("title 必须从候选里原样选一个");
        }
        return violations;
    }

    /** 快照事实覆盖模型自述，模型只负责旁白。 */
    private static ObjectNode withEvidence(JsonNode draft, JsonNode snapshot) {
        ObjectNode script = (ObjectNode) draft;
        script.put("dataset_sha256", snapshot.path("data_sha256").asString(""));
        script.put("source_key", snapshot.path("source_key").asString(""));
        script.put("chart", DatasetScriptContract.chartFor(snapshot.path("shape").asString("")));
        String attribution = snapshot.path("license").path("attribution").asString("");
        script.put("source_name", attribution.isBlank() ? snapshot.path("source_name").asString("") : attribution);
        return script;
    }

    private static String prompt(JsonNode snapshot, List<String> titles, boolean digest) {
        StringBuilder text = new StringBuilder();
        if (digest) {
            text.append("这是一条「今日数据速览」，不是热点解读，开头点明这是例行盘点。\n");
        }
        text.append("数据快照：\n").append(snapshot).append('\n')
            .append("标题候选（title 必须与下面某一项完全一致，原样复制）：\n");
        for (String title : titles) {
            text.append("- ").append(title).append('\n');
        }
        return text.append("按契约输出完整脚本 JSON。旁白只能用 {{facts.<路径>}} 引用数字，正文不得出现任何阿拉伯数字。")
            .toString();
    }

    private static String repairPrompt(JsonNode script, List<String> violations, List<String> titles) {
        return new StringBuilder()
            .append("上一版脚本没有通过契约校验，问题如下：\n")
            .append("- ").append(String.join("\n- ", violations)).append('\n')
            .append("title 只能从这些候选里原样选一个：\n")
            .append("- ").append(String.join("\n- ", titles)).append('\n')
            .append("只修改不符合要求的字段，其余字段原样保留，输出完整 JSON。\n")
            .append("超长的字段压到上限以下并留 10 字余量，不要贴着上限写；宁可短，不能被裁。\n")
            .append("上一版脚本：\n").append(script.toString()).toString();
    }

    private static JsonNode parse(String text) {
        JsonNode node = text == null ? null : JSON.readTree(ModelJson.unwrap(text));
        if (node == null || !node.isObject()) {
            throw new HeronBusinessException(409, "dataset_script_contract", "脚本退回写稿：模型输出不是 JSON 对象");
        }
        return node;
    }

    private static String resource(String path) {
        try (InputStream stream = DatasetScriptAuthor.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
