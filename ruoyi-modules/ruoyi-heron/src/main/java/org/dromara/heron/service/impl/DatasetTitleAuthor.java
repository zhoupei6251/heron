package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 标题专用调用：按五类钩子各出一个候选，再由写稿调用结合数据选一个。
 * 方向、长度、禁语在代码里硬校验，不指望模型自觉。
 *
 * <p>有策划结论（story/1）时，钩子按叙事类型收窄，标题里引用的数字路径必须落在故事信封内。
 */
@Service
@RequiredArgsConstructor
public class DatasetTitleAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/data-board/title-contract.md");
    private static final String STAGE = "dataset-title";
    /** 标题契约的五类钩子；落 content_profile 前必须仍是这五类之一，未知一律不落。 */
    static final java.util.Set<String> KINDS = java.util.Set.of(
        "数字反差", "疑问悬念", "利益相关", "时间紧迫", "反常识");
    /** 禁语：投资建议与预测口吻。story 契约的 stakes 也要复用这份词表，所以保持包可见。 */
    static final List<String> BANNED = List.of(
        "必涨", "必跌", "稳赚", "抄底", "逃顶", "预测", "将涨", "将跌", "一定", "保证");

    private final OpenAiModelClient model;

    /** 一个标题候选：文本与它属于哪类钩子。kind 写稿时被丢掉过一次，归因就再也取不回来。 */
    record Candidate(String title, String kind) { }

    public List<Candidate> candidates(ProductionRunVo run, String datasetJson) {
        return candidates(run, datasetJson, "");
    }

    /** 趋势线多给一段判定摘要：标题要问「变化意味着什么」，不能只复述数值。 */
    public List<Candidate> candidates(ProductionRunVo run, String datasetJson, String context) {
        return candidates(run, datasetJson, context, null);
    }

    /**
     * 有策划结论时的标题候选。
     *
     * <p>钩子按叙事类型收窄：同一份策划结论只配相容的钩子（见
     * {@link DatasetStoryContract#titleKinds(String)}），标题引用的路径也必须落在故事信封内，
     * 否则标题就在讲策划之外的数字。
     */
    public List<Candidate> candidates(ProductionRunVo run, String datasetJson, String context, EditorialStory story) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        String pack = ColumnPacks.text(snapshot.path("source_key").asString(""));
        String reply = model.chat(run, STAGE, SYSTEM + "\n\n---\n\n" + pack, prompt(snapshot, context, story));
        return parse(reply, snapshot, story);
    }

    static List<Candidate> parse(String reply, JsonNode snapshot) {
        return parse(reply, snapshot, null);
    }

    static List<Candidate> parse(String reply, JsonNode snapshot, EditorialStory story) {
        JsonNode node;
        try {
            node = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw contract("标题输出不是 JSON");
        }
        JsonNode candidates = node.path("candidates");
        if (!candidates.isArray()) throw contract("缺少 candidates");
        Set<String> kinds = story == null ? KINDS : DatasetStoryContract.titleKinds(story.narrative());
        List<Candidate> valid = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            String title = candidate.path("title").asString("").strip();
            String kind = candidate.path("kind").asString("").strip();
            if (title.isEmpty() || !valid(title, snapshot, story) || !kinds.contains(kind)) continue;
            if (valid.stream().anyMatch(item -> item.title().equals(title))) continue;
            valid.add(new Candidate(title, kind));
        }
        int minimum = story == null ? 3 : 1;
        if (valid.size() < minimum) {
            throw contract("有效标题不足 " + minimum + " 个：" + valid.stream().map(Candidate::title).toList());
        }
        return List.copyOf(valid.subList(0, Math.min(5, valid.size())));
    }

    static boolean valid(String title, JsonNode snapshot) {
        return valid(title, snapshot, null);
    }

    /** 标题能不能用：有故事时，引用一条信封外的路径就不算。 */
    static boolean valid(String title, JsonNode snapshot, EditorialStory story) {
        if (title == null || title.isBlank()) return false;
        int precision = snapshot.path("metric").path("precision").asInt(2);
        String resolved;
        try {
            resolved = DatasetPlaceholders.fill(title, snapshot, precision);
        } catch (RuntimeException e) {
            return false;
        }
        int length = resolved.codePointCount(0, resolved.length());
        if (length < 4 || length > 15) return false;
        for (String banned : BANNED) {
            if (title.contains(banned)) return false;
        }
        double change = snapshot.path("facts").path("change").asDouble(0);
        boolean up = containsAny(title, "涨", "升", "走高", "新高", "反弹", "上行");
        boolean down = containsAny(title, "跌", "降", "走低", "新低", "回落", "下行");
        if (up && change < 0) return false;
        if (down && change > 0) return false;
        if (story != null) {
            Set<String> envelope = new LinkedHashSet<>(story.allEvidenceRefs());
            for (String path : StoryScriptAlignment.factPaths(title)) {
                if (!envelope.contains(path)) return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static String prompt(JsonNode snapshot, String context, EditorialStory story) {
        String extra = context == null || context.isBlank() ? "" : context.strip() + "\n\n";
        if (story == null) {
            return extra + "数据快照：\n" + snapshot + "\n按契约输出五个候选，每个 kind 一个。";
        }
        Set<String> kinds = DatasetStoryContract.titleKinds(story.narrative());
        return extra + "数据快照：\n" + snapshot
            + "\n已定故事（策划结论，标题必须与它相容）：\n"
            + "一句话：" + story.oneLine()
            + "\n张力：" + (story.tension() == null ? "" : story.tension().statement())
            + "\n允许的标题钩子只有：" + String.join("、", kinds)
            + "\n标题里能用的数字路径只有：" + String.join("、", story.allEvidenceRefs())
            + "\n按契约输出候选，每个允许的 kind 至少一个。";
    }

    private static HeronBusinessException contract(String reason) {
        return new HeronBusinessException(409, "dataset_title_contract", "标题退回重写：" + reason);
    }

    private static String resource(String path) {
        try (InputStream stream = DatasetTitleAuthor.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
