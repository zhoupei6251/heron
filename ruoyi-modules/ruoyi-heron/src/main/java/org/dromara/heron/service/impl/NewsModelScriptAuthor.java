package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 稿件由模型写，出处事实由抓取给：模型自述的 source_url / fetched_at / body_sha256 一律被抓取结果覆盖，
 * 契约不过就列条目退回，不改稿也不重试。
 *
 * <p>正文由流水线传入（已冻结）。写稿阶段不再出网：同一任务重试时读到的就是同一份正文。
 */
@Service
@RequiredArgsConstructor
public class NewsModelScriptAuthor implements NewsScriptAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 契约管形状，风格管说人话；两份一起给模型，避免"符合格式但没人听"。 */
    private static final String SYSTEM = resource("/prompt/news-explainer/writing-style.md")
        + "\n\n---\n\n" + resource("/prompt/news-explainer/script-contract.md");

    private final OpenAiModelClient model;
    private final FrozenArticleService articles;

    @Override
    public String author(ProductionRunVo run) {
        JsonNode input = JSON.readTree(run.inputJson());
        NewsArticleBody article = articles.of(run.taskId(), input);
        ObjectNode script = withEvidence(parse(model.chat(run, "script", SYSTEM, prompt(input, article))), article);
        List<String> violations = NewsScriptContract.violations(script);
        if (!violations.isEmpty()) {
            // 一次定向修复：把违规条目原样回给模型，只改不合规的字段；再不过就按规则失败，不盲目重跑
            ObjectNode repaired = withEvidence(
                parse(model.chat(run, "script-repair", SYSTEM, repairPrompt(script, violations))), article);
            violations = NewsScriptContract.violations(repaired);
            if (!violations.isEmpty()) {
                throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：" + String.join("；", violations));
            }
            script = repaired;
        }
        return JSON.writeValueAsString(script);
    }

    /** 审稿判 BLOCKER/MAJOR 后的定向修复：只改被点出的问题，其余字段原样保留。 */
    @Override
    public String repair(ProductionRunVo run, String scriptJson, List<String> notes) {
        JsonNode input = JSON.readTree(run.inputJson());
        NewsArticleBody article = articles.of(run.taskId(), input);
        ObjectNode script = withEvidence(
            parse(model.chat(run, "script-repair", SYSTEM, reviewRepairPrompt(scriptJson, notes))), article);
        List<String> violations = NewsScriptContract.violations(script);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "news_script_contract", "修复稿仍未过契约：" + String.join("；", violations));
        }
        return JSON.writeValueAsString(script);
    }

    private static String reviewRepairPrompt(String scriptJson, List<String> notes) {
        return "上一版稿件没有通过审稿，问题如下：\n- " + String.join("\n- ", notes)
            + "\n只修改被点出的问题，其余字段与数字原样保留，输出完整 JSON。\n"
            + "上一版稿件：\n" + scriptJson;
    }

    /** 出处事实由抓取覆盖，模型自述一律不采信。 */
    private static ObjectNode withEvidence(JsonNode draft, NewsArticleBody article) {
        ObjectNode script = (ObjectNode) draft;
        script.put("source_url", article.url());
        script.put("fetched_at", article.fetchedAt() == null ? "" : article.fetchedAt().toString());
        script.put("body_sha256", article.htmlSha256());
        return script;
    }

    private static String repairPrompt(ObjectNode script, List<String> violations) {
        return new StringBuilder()
            .append("上一版稿件没有通过契约校验，问题如下：\n")
            .append("- ").append(String.join("\n- ", violations)).append('\n')
            .append("只修改不符合要求的字段，其余字段原样保留，输出完整 JSON。\n")
            .append("上一版稿件：\n").append(script.toString()).toString();
    }

    /**
     * 提示词：编辑意图、决策理由、故事视角必须进模型输入。
     * 这些字段躺在快照里不读，等于总编辑白干——「决策失效」就是这么来的。
     * 老快照（手工路径）没有 editorial 节点，对应的行整行省略，不塞空字符串。
     */
    static String prompt(JsonNode input, NewsArticleBody article) {
        ArrayNode images = ArticleImages.extract(article.html(), article.url());
        StringBuilder text = new StringBuilder()
            .append("标题：").append(input.path("title").asString("")).append('\n')
            .append("草稿：").append(input.path("draft").asString("")).append('\n');
        appendEditorial(text, input.path("editorial"));
        text.append("出处：").append(article.url())
            .append("（抓取时间 ").append(article.fetchedAt()).append("）\n")
            .append("正文 HTML：\n").append(article.html()).append('\n')
            .append(ArticleImages.promptHint(images)).append('\n')
            .append("只用上面正文里能站住的事实，按契约输出完整稿件 JSON。");
        return text.toString();
    }

    private static void appendEditorial(StringBuilder text, JsonNode editorial) {
        String intent = editorial.path("editorialIntent").asString("").strip();
        String reason = editorial.path("reason").asString("").strip();
        String angle = editorial.path("storyAngle").asString("").strip();
        if (intent.isBlank() && reason.isBlank() && angle.isBlank()) {
            return;
        }
        text.append("编辑意图：").append(intent.isBlank() ? "（未给出）" : intent).append('\n');
        if (!reason.isBlank()) {
            text.append("为什么选它：").append(reason).append('\n');
        }
        if (!angle.isBlank()) {
            text.append("故事视角：").append(angle).append('\n');
        }
    }

    private static JsonNode parse(String text) {
        JsonNode node = text == null ? null : JSON.readTree(ModelJson.unwrap(text));
        if (node == null || !node.isObject()) {
            throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：模型输出不是 JSON 对象");
        }
        return node;
    }

    private static String resource(String path) {
        try (InputStream stream = NewsModelScriptAuthor.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
