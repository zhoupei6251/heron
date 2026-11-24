package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 改写器只做两件事：按意见让模型产出一份新故事，产不出来就抛。
 * 校验不在这一层——生产侧同一处闸会把不合格的改写挡回去。
 */
@DisplayName("策划结论改写：产不出结构就抛，不让半成品进生产")
class StoryRewriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OpenAiModelClient model = mock(OpenAiModelClient.class);
    private final HeronNewsWorkMapper works = mock(HeronNewsWorkMapper.class);
    private final HeronVideoVersionMapper versions = mock(HeronVideoVersionMapper.class);
    private final DatasetFetchService datasets = mock(DatasetFetchService.class);

    private StoryRewriter rewriter() {
        return new StoryRewriter(model, works, versions, datasets);
    }

    /** 当前版本：冻结快照里有选材与故事，取数口径也在里面。 */
    private void givenCurrentVersion(boolean withStory) {
        HeronNewsWork work = new HeronNewsWork();
        work.setId(1L);
        work.setCurrentVersionId(2L);
        when(works.selectById(1L)).thenReturn(work);
        HeronVideoVersion version = new HeronVideoVersion();
        version.setInputSnapshot(JSON.writeValueAsString(withStory
            ? Map.of("datasetWindow", Map.of("sourceKey", "sina-gold-xau", "points", 10),
                "selection", Map.of("dataSourceKey", "sina-gold-xau",
                    "story", Map.of("schemaVersion", "story/1", "oneLine", "原稿")))
            : Map.of("selection", Map.of("dataSourceKey", "sina-gold-xau"))));
        when(versions.selectById(2L)).thenReturn(version);
        when(datasets.snapshot(anyString(), anyInt()))
            .thenReturn(JSON.readTree("{\"facts\":{\"last\":{\"value\":120}}}"));
    }

    @Test
    @DisplayName("模型给出 JSON：原样返回改写后的故事")
    void returnsRewrittenStory() {
        givenCurrentVersion(true);
        when(model.chatWithoutUsage(anyString(), anyString()))
            .thenReturn("{\"schemaVersion\":\"story/1\",\"oneLine\":\"改过的那句\"}");

        Map<String, Object> rewritten = rewriter().rewrite(1L, "审稿说引用解析不到");

        assertEquals("改过的那句", rewritten.get("oneLine"));
    }

    @Test
    @DisplayName("模型没给 JSON：抛错，不把半成品送进生产")
    void rejectsNonJson() {
        givenCurrentVersion(true);
        when(model.chatWithoutUsage(anyString(), anyString())).thenReturn("我觉得可以这样改");

        HeronBusinessException failure = assertThrows(HeronBusinessException.class,
            () -> rewriter().rewrite(1L, "审稿说引用解析不到"));

        assertEquals("story_rewrite_invalid", failure.getCode());
    }

    @Test
    @DisplayName("没有策划结论：404，不去凭空编一条")
    void missingStoryRejected() {
        givenCurrentVersion(false);

        HeronBusinessException failure = assertThrows(HeronBusinessException.class,
            () -> rewriter().rewrite(1L, "审稿意见"));

        assertEquals("news_story_not_found", failure.getCode());
    }
}
