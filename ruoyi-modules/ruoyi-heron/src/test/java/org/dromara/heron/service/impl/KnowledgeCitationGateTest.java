package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.lane.knowledge.KnowledgePack;
import org.dromara.heron.service.impl.shared.production.NewsProductionPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 引用闸：可定位、清单一致、署名齐全、没有照抄。 */
@DisplayName("知识解释线：citation 闸")
class KnowledgeCitationGateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static KnowledgePack pack;

    @BeforeAll
    static void loadPack() {
        Path repository = Path.of("..", "..").toAbsolutePath().normalize();
        pack = KnowledgePack.load(repository.resolve("data").resolve("knowledge"), "how-to-live-better");
    }

    private static String script(String closing, String... entryIdsPerScene) {
        ObjectNode script = JSON.createObjectNode();
        script.put("closing", closing);
        ArrayNode scenes = script.putArray("scenes");
        for (int index = 0; index < entryIdsPerScene.length; index++) {
            ObjectNode scene = scenes.addObject();
            scene.put("lead", "第 " + (index + 1) + " 条建议");
            scene.put("body", "这一屏用自己的话说清楚该做什么。");
            scene.put("duration_s", 10);
            ArrayNode ids = scene.putArray("entry_ids");
            if (!entryIdsPerScene[index].isBlank()) {
                ids.add(entryIdsPerScene[index]);
            }
        }
        return script.toString();
    }

    private static String citations(String scriptJson) {
        return pack.citationsJson(NewsProductionPipeline.sceneRefs(JSON.readTree(scriptJson)));
    }

    @Test
    @DisplayName("引用都能定位、清单一致、署名齐全时通过")
    void passesResolvableCitations() {
        String json = script("把这几件事先做完。 " + pack.attribution(), "05-01", "05-02");
        // 通过即不抛：Gate 是流水线内部类型，不断言它的字段
        NewsProductionPipeline.citationGate(JSON.readTree(json), citations(json), pack, 20);
    }

    @Test
    @DisplayName("引用了知识包里没有的条目就拒")
    void rejectsUnknownEntry() {
        String json = script("收个尾。 " + pack.attribution(), "99-99");
        assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.citationGate(JSON.readTree(json), citations(json), pack, 20));
    }

    @Test
    @DisplayName("某段没挂 entryId 就拒")
    void rejectsSceneWithoutEntry() {
        String json = script("收个尾。 " + pack.attribution(), "05-01", "");
        assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.citationGate(JSON.readTree(json), citations(json), pack, 20));
    }

    @Test
    @DisplayName("片尾缺署名原文就拒：CC BY 4.0 的署名义务不能靠模型自觉")
    void rejectsMissingAttribution() {
        String json = script("把这几件事先做完。", "05-01");
        assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.citationGate(JSON.readTree(json), citations(json), pack, 20));
    }

    @Test
    @DisplayName("连续照抄原文超过上限就拒")
    void rejectsVerbatimCopy() {
        KnowledgePack.Entry entry = pack.entry("05-01");
        String copied = entry.plain().replaceAll("\\s+", "");
        ObjectNode script = (ObjectNode) JSON.readTree(script("收个尾。 " + pack.attribution(), "05-01"));
        ObjectNode scene = (ObjectNode) script.path("scenes").get(0);
        scene.put("body", copied.length() > 24 ? copied.substring(0, 24) : copied);
        String json = script.toString();
        assertThrows(HeronBusinessException.class,
            () -> NewsProductionPipeline.citationGate(JSON.readTree(json), citations(json), pack, 20));
    }

    @Test
    @DisplayName("最长连续相同片段按字符算，不看空白")
    void longestRunCountsCharacters() {
        assertEquals(5, NewsProductionPipeline.longestCommonRun("abc def", "xabcdey"));
        assertEquals(0, NewsProductionPipeline.longestCommonRun("", "任意文本"));
        assertEquals(4, NewsProductionPipeline.longestCommonRun("甲乙丙丁", "戊甲乙丙丁己"));
    }
}
