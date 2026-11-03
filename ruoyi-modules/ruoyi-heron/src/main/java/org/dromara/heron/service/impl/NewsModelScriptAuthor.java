package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.NewsScriptAuthor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 稿件由模型写，出处事实由抓取给：模型自述的 source_url / fetched_at / body_sha256 一律被抓取结果覆盖，
 * 契约不过就列条目退回，不改稿也不重试。
 */
@Service
@RequiredArgsConstructor
public class NewsModelScriptAuthor implements NewsScriptAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONTRACT = contract();

    private final OpenAiModelClient model;
    private final NewsSourceFetcher fetcher;

    @Override
    public String author(ProductionRunVo run) {
        JsonNode input = JSON.readTree(run.inputJson());
        NewsArticleBody article = fetcher.fetchArticle(sourceUrl(input));
        JsonNode draft = parse(model.chat(run, "script", CONTRACT, prompt(input, article)));
        ObjectNode script = (ObjectNode) draft;
        script.put("source_url", article.url());
        script.put("fetched_at", article.fetchedAt().toString());
        script.put("body_sha256", article.htmlSha256());
        List<String> violations = NewsScriptContract.violations(script);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：" + String.join("；", violations));
        }
        return JSON.writeValueAsString(script);
    }

    /** 泛资讯候选必须带正文出处；手工候选没有出处时直接失败，不假装模型知识是来源。 */
    private static URI sourceUrl(JsonNode input) {
        for (JsonNode item : input.path("evidence").path("items")) {
            String url = item.path("sourceUrl").asString("");
            if (!url.isBlank()) {
                try {
                    return URI.create(url);
                } catch (IllegalArgumentException e) {
                    throw new HeronBusinessException(409, "news_script_source", "候选证据里的正文链接无法解析");
                }
            }
        }
        throw new HeronBusinessException(409, "news_script_source", "候选证据里没有正文出处，不能写稿");
    }

    private static String prompt(JsonNode input, NewsArticleBody article) {
        return new StringBuilder()
            .append("标题：").append(input.path("title").asString("")).append('\n')
            .append("草稿：").append(input.path("draft").asString("")).append('\n')
            .append("出处：").append(article.url()).append("（抓取时间 ").append(article.fetchedAt()).append("）\n")
            .append("正文 HTML：\n").append(article.html()).append('\n')
            .append("只用上面正文里能站住的事实，按契约输出完整稿件 JSON。").toString();
    }

    private static JsonNode parse(String text) {
        JsonNode node = text == null ? null : JSON.readTree(text);
        if (node == null || !node.isObject()) {
            throw new HeronBusinessException(409, "news_script_contract", "稿件退回写稿：模型输出不是 JSON 对象");
        }
        return node;
    }

    private static String contract() {
        try (InputStream resource = NewsModelScriptAuthor.class.getResourceAsStream("/heron/news-script-contract.md")) {
            if (resource == null) {
                throw new IllegalStateException("缺少 news-script-contract.md");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("稿件创作契约不可读", e);
        }
    }
}
