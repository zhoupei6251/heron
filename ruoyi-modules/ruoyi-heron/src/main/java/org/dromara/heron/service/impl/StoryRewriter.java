package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 审稿没过时的自动纠错：让模型按审稿意见改写 story/1，再由同一条生产口径重出片。
 *
 * <p>喂给模型的是「当前故事 + 这份数据的 facts + 审稿意见」三件东西，不放数据选材：
 * 改的是这条片子讲什么事，不是拿哪份数据。数字只能来自 facts，所以引用不会凭空长出来。
 *
 * <p>改写本身不做校验：校验在生产侧同一处（{@code NewsWorkServiceImpl#reproduceWithStoryForAuto}），
 * 改写器只管产出候选，过不了闸就退回换选题。
 */
@Service
@RequiredArgsConstructor
public class StoryRewriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/editorial/story-rewrite.md");

    private final OpenAiModelClient model;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final DatasetFetchService datasets;

    /**
     * 按审稿意见改写当前版本的策划结论。
     *
     * @param reviewReason 审稿失败原话，含 coreFacts 与逐条问题；模型据此定点修，不是重写一篇
     */
    public Map<String, Object> rewrite(long workId, String reviewReason) {
        HeronNewsWork work = workMapper.selectById(workId);
        if (work == null || work.getCurrentVersionId() == null) {
            throw notFound();
        }
        HeronVideoVersion version = versionMapper.selectById(work.getCurrentVersionId());
        if (version == null || version.getInputSnapshot() == null) {
            throw notFound();
        }
        JsonNode snapshot = JSON.readTree(version.getInputSnapshot());
        JsonNode selection = snapshot.path("selection");
        JsonNode story = selection.path("story");
        if (!story.isObject()) {
            throw notFound();
        }
        // 与生产同一条取数口径：口径不同，改写出来的引用生产那边照样解析不到。
        JsonNode dataset;
        try {
            dataset = datasets.snapshot(selection.path("dataSourceKey").asString(""),
                DatasetWindowContract.pointsOf(snapshot));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(503, "news_story_dataset_unavailable",
                "取数失败，没法改写策划结论：" + e.getMessage());
        }
        String reply = model.chatWithoutUsage(SYSTEM, prompt(story, dataset, reviewReason));
        JsonNode edited;
        try {
            edited = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw new HeronBusinessException(409, "story_rewrite_invalid", "改写结果不是 JSON：" + e.getMessage());
        }
        if (edited == null || !edited.isObject()) {
            throw new HeronBusinessException(409, "story_rewrite_invalid", "改写结果不是 JSON 对象");
        }
        return JSON.convertValue(edited, Map.class);
    }

    /** 只给 facts，不给整份行数据：能引用的键就是这张表里的键，模型不可能编出解析不到的引用。 */
    private static String prompt(JsonNode story, JsonNode dataset, String reviewReason) {
        return "当前策划结论（story/1）：\n" + JSON.writeValueAsString(story)
            + "\n\n这份数据的事实表（数字与引用的唯一来源，只能引用 facts 下已存在的键）：\n"
            + JSON.writeValueAsString(dataset.path("facts"))
            + "\n\n审稿意见：\n" + (reviewReason == null ? "未给出" : reviewReason)
            + "\n\n按意见改写，只输出 JSON。";
    }

    private static HeronBusinessException notFound() {
        return new HeronBusinessException(404, "news_story_not_found", "这条作品还没有可改写的策划结论");
    }

    private static String resource(String path) {
        try (InputStream stream = StoryRewriter.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
