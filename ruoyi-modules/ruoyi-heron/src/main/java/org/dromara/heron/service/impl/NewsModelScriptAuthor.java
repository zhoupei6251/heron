package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.config.HeronProductionProperties;
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
import java.nio.file.Path;
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
    private final HeronProductionProperties properties;

    @Override
    public String author(ProductionRunVo run) {
        JsonNode input = JSON.readTree(run.inputJson());
        NewsArticleBody article = articles.of(run.taskId(), input);
        SceneCapability sceneCapability = sceneCapability(input);
        ObjectNode script = withEvidence(parse(model.chat(run, "script", SYSTEM,
            prompt(input, article, sceneCapability.prompt()))), article);
        List<String> violations = violations(script, sceneCapability.storyboard());
        if (!violations.isEmpty()) {
            // 一次定向修复：把违规条目原样回给模型，只改不合规的字段；再不过就按规则失败，不盲目重跑
            ObjectNode repaired = withEvidence(
                parse(model.chat(run, "script-repair", SYSTEM,
                    repairPrompt(script, violations, sceneCapability.prompt()))), article);
            violations = violations(repaired, sceneCapability.storyboard());
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
        SceneCapability sceneCapability = sceneCapability(input);
        ObjectNode script = withEvidence(
            parse(model.chat(run, "script-repair", SYSTEM,
                reviewRepairPrompt(scriptJson, notes, sceneCapability.prompt()))), article);
        boolean storyboardRequired = sceneCapability.storyboard() && hasStoryboardSchema(scriptJson);
        List<String> violations = violations(script, storyboardRequired);
        if (!violations.isEmpty()) {
            throw new HeronBusinessException(409, "news_script_contract", "修复稿仍未过契约：" + String.join("；", violations));
        }
        return JSON.writeValueAsString(script);
    }

    private static String reviewRepairPrompt(String scriptJson, List<String> notes, String sceneCapability) {
        return "上一版稿件没有通过审稿，问题如下：\n- " + String.join("\n- ", notes)
            + "\n只修改被点出的问题，其余字段与数字原样保留，输出完整 JSON。\n"
            + sceneCapability + "\n上一版稿件：\n" + scriptJson;
    }

    /** 出处事实由抓取覆盖，模型自述一律不采信。 */
    private static ObjectNode withEvidence(JsonNode draft, NewsArticleBody article) {
        ObjectNode script = (ObjectNode) draft;
        script.put("source_url", article.url());
        script.put("fetched_at", article.fetchedAt() == null ? "" : article.fetchedAt().toString());
        script.put("body_sha256", article.htmlSha256());
        return script;
    }

    private static String repairPrompt(ObjectNode script, List<String> violations, String sceneCapability) {
        return new StringBuilder()
            .append("上一版稿件没有通过契约校验，问题如下：\n")
            .append("- ").append(String.join("\n- ", violations)).append('\n')
            .append("只修改不符合要求的字段，其余字段原样保留，输出完整 JSON。\n")
            .append(sceneCapability).append('\n')
            .append("上一版稿件：\n").append(script.toString()).toString();
    }

    /** 旧无 beat 稿继续按原 schema 修复，不能因模板后来启用分镜而被迫升级。 */
    private static boolean hasStoryboardSchema(String scriptJson) {
        try {
            JsonNode original = JSON.readTree(scriptJson);
            JsonNode scenes = original.path("scenes");
            if (!scenes.isArray() || scenes.isEmpty()) {
                return false;
            }
            for (JsonNode scene : scenes) {
                if (scene.path("scene_type").asString("").isBlank()
                    || scene.path("beat").asString("").isBlank()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "news_script_contract", "待修复稿件不是有效 JSON", e);
        }
    }

    /** 形状与分镜一起进写稿修复循环，避免渲到闸口才发现节点、组件或上屏文字不成立。 */
    static List<String> violations(JsonNode script, boolean storyboardRequired) {
        List<String> shape = NewsScriptContract.violations(script);
        if (script == null || !script.isObject()) {
            return shape;
        }
        java.util.Set<String> items = new java.util.LinkedHashSet<>(shape);
        if (storyboardRequired) {
            JsonNode scenes = script.path("scenes");
            if (scenes.isArray()) {
                for (int index = 0; index < scenes.size(); index++) {
                    JsonNode scene = scenes.get(index);
                    if (!scene.isObject()) {
                        continue;
                    }
                    int number = index + 1;
                    if (scene.path("scene_type").asString("").isBlank()) {
                        items.add("第 " + number + " 段缺少 scene_type");
                    }
                    if (scene.path("beat").asString("").isBlank()) {
                        items.add("第 " + number + " 段缺少 beat");
                    }
                    if (!scene.has("on_screen_text") || scene.path("on_screen_text").asString("").isBlank()) {
                        items.add("第 " + number + " 段缺少 on_screen_text");
                    }
                }
            }
        }
        items.addAll(StoryboardContract.violations(script, script));
        return List.copyOf(items);
    }

    /**
     * 提示词：编辑意图、决策理由、故事视角必须进模型输入。
     * 这些字段躺在快照里不读，等于总编辑白干——「决策失效」就是这么来的。
     * 老快照（手工路径）没有 editorial 节点，对应的行整行省略，不塞空字符串。
     */
    static String prompt(JsonNode input, NewsArticleBody article, String sceneCapability) {
        ArrayNode images = ArticleImages.extract(article.html(), article.url());
        StringBuilder text = new StringBuilder()
            .append("标题：").append(input.path("title").asString("")).append('\n')
            .append("草稿：").append(input.path("draft").asString("")).append('\n');
        appendEditorial(text, input.path("editorial"));
        text.append("出处：").append(article.url())
            .append("（抓取时间 ").append(article.fetchedAt()).append("）\n")
            .append("正文 HTML：\n").append(article.html()).append('\n')
            .append(ArticleImages.promptHint(images)).append('\n')
            .append("只用上面正文里能站住的事实，按契约输出完整稿件 JSON。").append(sceneCapability);
        return text.toString();
    }

    record SceneCapability(String prompt, boolean storyboard) { }

    /**
     * 把本次模板能渲染的场景类型告诉模型。
     * 模板不支持的类型写了也过不了渲染闸，与其让模型猜，不如在写稿前就把范围收窄。
     */
    SceneCapability sceneCapability(JsonNode input) {
        String templateKey = input.path("assets").path("templateKey").asString("");
        Path root = properties.getNewsTemplate();
        if (templateKey.isBlank() || root == null) {
            return new SceneCapability("", false);
        }
        Path templateDir = root.resolve(templateKey);
        java.util.Set<String> types = NewsProductionPipeline.supportedSceneTypes(templateDir);
        StringBuilder capability = new StringBuilder();
        if (!types.isEmpty()) {
            capability.append("\n本次模板（").append(templateKey).append("）只支持这些 scene_type，只能用这些：")
                .append(String.join("、", types)).append("。其余类型一律不要写。");
        }
        Path config = templateDir.resolve("template.config.json");
        if (!java.nio.file.Files.isRegularFile(config)) {
            return new SceneCapability(capability.toString(), false);
        }
        try {
            JsonNode node = JSON.readTree(java.nio.file.Files.readString(config, StandardCharsets.UTF_8));
            boolean storyboard = node.path("storyboard").asBoolean(false);
            if (storyboard) {
                capability.append("\n本次模板（").append(templateKey)
                    .append("）已启用分镜：每段必须同时写 scene_type、beat、on_screen_text；")
                    .append("首段 beat=HOOK，末段 beat=CONCLUSION/INSIGHT/REVEAL，整篇至少两种 scene_type；")
                    .append("on_screen_text 不超过 18 字且不能复制 lead/body；")
                    .append("body 保留完整口播，不得为空，title 保留独立标题卡。");
            }
            return new SceneCapability(capability.toString(), storyboard);
        } catch (RuntimeException | IOException e) {
            throw new HeronBusinessException(409, "news_template_config",
                "模板分镜配置不可读：" + templateKey, e);
        }
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
