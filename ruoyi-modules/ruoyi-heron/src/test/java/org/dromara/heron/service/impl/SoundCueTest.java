package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.service.impl.shared.media.SoundCue;
import org.dromara.heron.service.impl.shared.media.SoundCueMixer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 音效规划：只接受真实场景窗，缺素材必须留下可审计的跳过记录。 */
@DisplayName("声音提示规划")
class SoundCueTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<SoundCue.Window> SCENES = List.of(
        new SoundCue.Window("scene-1", 0, 10_000),
        new SoundCue.Window("scene-2", 10_000, 20_000),
        new SoundCue.Window("closing-slot", 20_000, 30_000));
    private static final List<SoundCue.Window> NARRATION = List.of(
        new SoundCue.Window("sentence-1", 500, 2_500),
        new SoundCue.Window("sentence-2", 10_500, 12_000),
        new SoundCue.Window("sentence-3", 20_500, 22_000));

    @TempDir
    Path assets;

    @Test
    @DisplayName("whoosh 落在实际切点，数字 tick 落在数字屏，结论强调落在实际片尾")
    void plansCuesOnValidatedWindows() throws Exception {
        seedAssets();
        JsonNode script = JSON.readTree("""
            {"scenes":[
              {"on_screen_text":"增幅 12.5%","lead":"十二点五","body":"持续增长"},
              {"on_screen_text":"趋势向上","lead":"趋势","body":"没有回落"}
            ],"closing":"结论已经明确"}
            """);

        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION, script, assets);

        assertEquals(3, plan.cues().size());
        assertEquals(SoundCue.Type.TRANSITION_WHOOSH, plan.cues().get(0).type());
        assertEquals(10_000, plan.cues().get(0).atMs());
        assertEquals("scene-2", plan.cues().get(0).sceneId());
        assertEquals(SoundCue.Type.NUMERIC_TICK, plan.cues().get(1).type());
        assertEquals(0, plan.cues().get(1).atMs());
        assertEquals(SoundCue.Type.CONCLUSION_EMPHASIS, plan.cues().get(2).type());
        assertEquals(20_000, plan.cues().get(2).atMs());
        assertTrue(plan.checks().isEmpty(), "素材齐全时不应伪造跳过记录");
    }

    @Test
    @DisplayName("缺 whoosh 只跳过转场，不缺的 tick 与结论仍保留")
    void missingAssetSkipsOnlyThatCueType() throws Exception {
        seedAssets();
        Files.delete(assets.resolve("whoosh-short.mp3"));

        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION, script(), assets);

        assertEquals(2, plan.cues().size());
        assertTrue(plan.cues().stream().noneMatch(cue -> cue.type() == SoundCue.Type.TRANSITION_WHOOSH));
        assertEquals(1, plan.checks().size());
        Map<String, Object> check = plan.checks().getFirst().toMap();
        assertEquals(Set.of("gate", "status", "code", "inputLocator", "passed", "reason", "measured"), check.keySet());
        assertEquals("SKIP", check.get("status"));
        assertEquals("MISSING_SFX_ASSET", check.get("code"));
        assertEquals("sfx", check.get("gate"));
        assertTrue(check.get("inputLocator").toString().matches("[a-zA-Z0-9_.:#\\[\\]-]{1,256}"));
        assertEquals(true, check.get("passed"), "SKIP 已处理但不代表渲染成功");
        assertFalse(check.get("reason").toString().isBlank());
        Map<?, ?> measured = (Map<?, ?>) check.get("measured");
        assertEquals(true, measured.get("skipped"));
        assertEquals(true, measured.get("missingAsset"));
        assertEquals(false, measured.get("rendered"));
        assertEquals(true, measured.get("transitionWhoosh"));
    }

    @Test
    @DisplayName("数字 tick 优先读上屏文字，仅在上屏字段缺省时回退 lead")
    void numericTickUsesOnlyVisualTextWithExplicitLeadFallback() throws Exception {
        seedAssets();
        JsonNode script = JSON.readTree("""
            {"scenes":[
              {"on_screen_text":"趋势向上","lead":"2026 年启动","body":"正文还有 2027"},
              {"lead":"12.5% 增长","body":"持续"},
              {"body":"正文出现 3 年"}
            ],"closing":"结论明确"}
            """);
        List<SoundCue.Window> scenes = List.of(
            new SoundCue.Window("scene-1", 0, 10_000),
            new SoundCue.Window("scene-2", 10_000, 20_000),
            new SoundCue.Window("scene-3", 20_000, 30_000),
            new SoundCue.Window("closing-slot", 30_000, 40_000));

        SoundCue.Plan plan = SoundCue.plan(scenes, List.of(), script, assets);

        List<SoundCue.Cue> ticks = plan.cues().stream()
            .filter(cue -> cue.type() == SoundCue.Type.NUMERIC_TICK).toList();
        assertEquals(1, ticks.size());
        assertEquals("scene-2", ticks.getFirst().sceneId());
        assertEquals(10_000, ticks.getFirst().atMs());
    }

    @Test
    @DisplayName("三类素材全缺时返回显式 SKIP，不返回静默成功")
    void allMissingAssetsAreExplicitSkips() throws Exception {
        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION, script(), assets);

        assertTrue(plan.cues().isEmpty());
        assertEquals(3, plan.checks().size());
        assertTrue(plan.checks().stream().allMatch(check -> "SKIP".equals(check.status())));
        assertTrue(plan.checks().stream().allMatch(SoundCue.Check::passed));
        assertTrue(plan.checks().stream().allMatch(check -> !check.toMap().get("measured").toString().contains("rendered=true")));
    }

    @Test
    @DisplayName("旁白内衰减 6 dB，旁白空隙只衰减 3 dB")
    void ducksOnlyThreeToSixDecibels() throws Exception {
        seedAssets();
        SoundCue.Plan plan = SoundCue.plan(SCENES, NARRATION, script(), assets);
        SoundCue.Cue tick = plan.cues().stream().filter(cue -> cue.type() == SoundCue.Type.NUMERIC_TICK)
            .findFirst().orElseThrow();

        SoundCue.Cue duringNarration = new SoundCue.Cue("test:tick", SoundCue.Type.NUMERIC_TICK,
            "scene-1", 500, tick.asset());
        String inNarration = SoundCueMixer.cueFilter(0, duringNarration, 400, NARRATION);
        String inGap = SoundCueMixer.cueFilter(0, tick, 400,
            List.of(new SoundCue.Window("sentence", 2_000, 2_500)));

        assertTrue(inNarration.contains("0.501187"), inNarration);
        assertFalse(inNarration.contains("0.707946"), inNarration);
        assertTrue(inGap.contains("0.707946"), inGap);
        assertFalse(inGap.contains("0.501187"), inGap);
        assertTrue(inNarration.contains("adelay=500:all=1"), inNarration);
        assertTrue(inGap.contains("adelay=0:all=1"), inGap);
    }

    @Test
    @DisplayName("场景窗有空洞、倒退或结尾不是 closing-slot 时拒绝估算")
    void rejectsUnvalidatedWindows() {
        HeronBusinessException gap = assertThrows(HeronBusinessException.class, () -> SoundCue.plan(
            List.of(SCENES.get(0), new SoundCue.Window("scene-2", 10_001, 20_000), SCENES.get(2)),
            NARRATION, script(), assets));
        assertEquals("news_sound_cue_alignment", gap.getCode());

        assertThrows(HeronBusinessException.class, () -> SoundCue.plan(
            List.of(new SoundCue.Window("scene-1", 0, 20_000), new SoundCue.Window("scene-2", 10_000, 30_000)),
            NARRATION, script(), assets));

        assertThrows(HeronBusinessException.class, () -> SoundCue.plan(
            List.of(SCENES.get(0), SCENES.get(1)), NARRATION, script(), assets));
    }

    @Test
    @DisplayName("旁白窗越界或重叠超过 250ms 时拒绝，与上游字幕契约一致")
    void rejectsInvalidNarrationWindows() {
        assertThrows(HeronBusinessException.class, () -> SoundCue.plan(SCENES,
            List.of(new SoundCue.Window("bad", 29_000, 31_000)), script(), assets));
        assertThrows(HeronBusinessException.class, () -> SoundCue.plan(SCENES,
            List.of(new SoundCue.Window("a", 500, 2_000), new SoundCue.Window("b", 1_500, 2_500)),
            script(), assets));
        assertThrows(HeronBusinessException.class, () -> SoundCue.plan(SCENES,
            List.of(new SoundCue.Window("a", 500, 2_000), new SoundCue.Window("b", 1_749, 2_500)),
            script(), assets));

        SoundCue.plan(SCENES,
            List.of(new SoundCue.Window("a", 500, 2_000), new SoundCue.Window("b", 1_750, 2_500)),
            script(), assets);
    }

    @Test
    @DisplayName("追加音效到现有旁白/BGM 总线时保留原滤镜并再次限幅")
    void appendsCueBedToExistingBus() {
        String bus = "[0:a]volume=1.000000,alimiter=limit=0.841395:level=disabled[vo];"
            + "[vo][1:a]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,"
            + "alimiter=limit=0.841395:level=disabled[mix]";

        String mixed = SoundCueMixer.mixIntoBus(bus, "[2:a]");

        assertTrue(mixed.contains("[vo][1:a]amix"), mixed);
        assertTrue(mixed.contains("[base][2:a]amix=inputs=2"), mixed);
        assertTrue(mixed.endsWith("alimiter=limit=0.812831:level=disabled[mix]"), mixed);
        assertThrows(HeronBusinessException.class, () -> SoundCueMixer.mixIntoBus("[0:a]anull[aout]", "[2:a]"));
    }

    private static JsonNode script() {
        return JSON.readTree("""
            {"scenes":[
              {"on_screen_text":"12.5%","lead":"十二点五","body":"增长"},
              {"on_screen_text":"趋势","lead":"趋势","body":"向上"}
            ],"closing":"结论明确"}
            """);
    }

    private void seedAssets() throws Exception {
        for (String name : List.of("whoosh-short.mp3", "click.mp3", "chime.mp3")) {
            Files.writeString(assets.resolve(name), "test asset");
        }
    }
}
