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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 稿件由模型写，出处事实由抓取给：模型自述的 source_url / fetched_at / body_sha256 一律被抓取结果覆盖，
 * 契约不过就列条目退回，不改稿也不重试。
 */
@Service
@RequiredArgsConstructor
public class NewsModelScriptAuthor implements NewsScriptAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 契约管形状，风格管说人话；两份一起给模型，避免"符合格式但没人听"。 */
    private static final String SYSTEM = resource("/prompt/news-explainer/writing-style.md")
        + "\n\n---\n\n" + resource("/prompt/news-explainer/script-contract.md");

    private final OpenAiModelClient model;
    private final NewsSourceFetcher fetcher;

    @Override
    public String author(ProductionRunVo run) {
        JsonNode input = JSON.readTree(run.inputJson());
        NewsArticleBody article = fetcher.fetchArticle(sourceUrl(input));
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
    public String repair(ProductionRunVo run, String scriptJson, List<String> notes) {
        JsonNode input = JSON.readTree(run.inputJson());
        NewsArticleBody article = fetcher.fetchArticle(sourceUrl(input));
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
        script.put("fetched_at", article.fetchedAt().toString());
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
     * 取正文出处：优先用线内编辑选中的那条证据，而不是列表里的第一条。
     *
     * <p>同一个事件常有多个来源，第一条未必是编辑选的那条。若仍取第一条，
     * 就会出现「快照记的证据是 A，写稿读的正文是 B」——和重新选数据源是同一类问题。
     * 选中的证据都取不到时才退回第一条，保证手工认领等路径仍可用。
     */
    private static URI sourceUrl(JsonNode input) {
        List<String> selected = new ArrayList<>();
        for (JsonNode id : input.path("selection").path("sourceEvidenceIds")) {
            String text = id.asString("");
            if (!text.isBlank()) {
                selected.add(text);
            }
        }
        URI chosen = firstUrl(input, item -> {
            String itemId = item.path("sourceItemId").asString("");
            String url = item.path("sourceUrl").asString("");
            return selected.contains(itemId) || selected.contains(url);
        });
        if (chosen != null) {
            return chosen;
        }
        URI fallback = firstUrl(input, item -> true);
        if (fallback != null) {
            return fallback;
        }
        throw new HeronBusinessException(409, "news_script_source", "候选证据里没有正文出处，不能写稿");
    }

    private static URI firstUrl(JsonNode input, java.util.function.Predicate<JsonNode> accept) {
        for (JsonNode item : input.path("evidence").path("items")) {
            String url = item.path("sourceUrl").asString("");
            if (url.isBlank() || !accept.test(item)) {
                continue;
            }
            try {
                return URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new HeronBusinessException(409, "news_script_source", "候选证据里的正文链接无法解析");
            }
        }
        return null;
    }

    private static String prompt(JsonNode input, NewsArticleBody article) {
        ArrayNode images = ArticleImages.extract(article.html(), article.url());
        return new StringBuilder()
            .append("标题：").append(input.path("title").asString("")).append('\n')
            .append("草稿：").append(input.path("draft").asString("")).append('\n')
            .append("出处：").append(article.url()).append("（抓取时间 ").append(article.fetchedAt()).append("）\n")
            .append("正文 HTML：\n").append(article.html()).append('\n')
            .append(ArticleImages.promptHint(images)).append('\n')
            .append("只用上面正文里能站住的事实，按契约输出完整稿件 JSON。").toString();
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
