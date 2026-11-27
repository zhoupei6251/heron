package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.shared.research.ContentProfileWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内容属性：只有生产期才知道的那两项，取不到就整个字段不写。 */
@DisplayName("表现反馈：内容属性")
class ContentProfileWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode script(String lead, String body) {
        return JSON.readTree("{\"title\":\"标题\",\"chart\":\"line\",\"scenes\":["
            + "{\"lead\":\"" + lead + "\",\"body\":\"" + body + "\",\"duration_s\":10},"
            + "{\"lead\":\"第二段\",\"body\":\"正文\",\"duration_s\":10}],\"closing\":\"结尾\"}");
    }

    @Test
    @DisplayName("钩子类型与开头结构都写下来")
    void writesHookTypeAndOpening() {
        JsonNode profile = ContentProfileWriter.write("数字反差",
            script("金价站上关键位", "最新收于 {{facts.last.value}} 美元。"));

        assertNotNull(profile);
        assertEquals("content-profile/1", profile.path("schemaVersion").asString());
        assertEquals("数字反差", profile.path("hookType").asString());
        assertEquals("data_first", profile.path("opening").path("shape").asString());
        assertTrue(profile.path("opening").path("hasFact").asBoolean());
        assertFalse(profile.path("opening").path("hasQuestion").asBoolean());
        assertNotNull(profile.path("writtenAt").asString());
    }

    @Test
    @DisplayName("首句是问句且没有事实引用时算提问优先")
    void questionFirstOpening() {
        JsonNode profile = ContentProfileWriter.write("疑问悬念", script("这波还能涨多久？", "答案在后面。"));

        assertNotNull(profile);
        assertEquals("question_first", profile.path("opening").path("shape").asString());
        assertTrue(profile.path("opening").path("hasQuestion").asBoolean());
    }

    @Test
    @DisplayName("既没有事实也没有问句时开头结构判不了，整个字段不写")
    void openingStaysOutWhenUnclassifiable() {
        JsonNode profile = ContentProfileWriter.write("数字反差", script("今天聊聊市场", "展开讲讲。"));

        assertNotNull(profile);
        assertEquals("数字反差", profile.path("hookType").asString());
        assertFalse(profile.has("opening"), "判不了就整个不写，不能猜一个标签进去");
    }

    @Test
    @DisplayName("钩子类型不在契约的五类里就不写")
    void unknownHookTypeIsNotWritten() {
        JsonNode profile = ContentProfileWriter.write("随便编的类型",
            script("金价站上关键位", "最新收于 {{facts.last.value}} 美元。"));

        assertNotNull(profile);
        assertFalse(profile.has("hookType"), "不在契约五类里的类型不能落库");
    }

    @Test
    @DisplayName("两项都取不到时整份内容属性为空")
    void nothingToWrite() {
        assertNull(ContentProfileWriter.write(null, script("今天聊聊市场", "展开讲讲。")));
    }

    @Test
    @DisplayName("序列化成可直接落库的字符串")
    void writesJsonString() {
        String json = ContentProfileWriter.writeJson("反常识",
            script("金价站上关键位", "最新收于 {{facts.last.value}} 美元。"));
        assertNotNull(json);
        assertTrue(json.contains("content-profile/1"), json);
    }
}
