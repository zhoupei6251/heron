package org.dromara.heron.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 配乐曲库：清单解析、线过滤、情绪节奏加权与权重摇号。 */
@DisplayName("配乐曲库选曲")
class BgmCatalogTest {

    private static BgmCatalog.Track track(String key, int weight, List<String> lines,
                                          List<String> emotions, List<String> rhythms) {
        return new BgmCatalog.Track(key, key + ".mp3", weight, Set.copyOf(lines),
            Set.copyOf(emotions), Set.copyOf(rhythms));
    }

    @Test
    @DisplayName("编辑线不符的曲目直接出局")
    void filtersByLine() {
        List<BgmCatalog.Track> tracks = List.of(
            track("only-knowledge", 100, List.of("KNOWLEDGE_EXPLAINER"), List.of("平静"), List.of("舒缓")),
            track("data-only", 10, List.of("DATA_BOARD"), List.of("平静"), List.of("舒缓")));

        BgmCatalog.Pick pick = BgmCatalog.pick(tracks, null, "DATA_BOARD", null, null, "seed");

        assertEquals("data-only", pick.track().key());
    }

    @Test
    @DisplayName("情绪节奏命中优先于权重")
    void affinityBeatsWeight() {
        List<BgmCatalog.Track> tracks = List.of(
            track("heavy-but-wrong", 100, List.of("DATA_BOARD"), List.of("平静"), List.of("舒缓")),
            track("light-but-right", 1, List.of("DATA_BOARD"), List.of("紧张"), List.of("紧凑")));

        BgmCatalog.Pick pick = BgmCatalog.pick(tracks, null, "DATA_BOARD", "紧张", "紧凑", "seed");

        assertEquals("light-but-right", pick.track().key());
        assertEquals(2, pick.measured().get("affinity"));
    }

    @Test
    @DisplayName("同一档内按权重摇号，高权重更容易中签")
    void weightsDecideWithinTier() {
        List<BgmCatalog.Track> tracks = List.of(
            track("default", 99, List.of("DATA_BOARD"), List.of("平静"), List.of("平稳")),
            track("rare", 1, List.of("DATA_BOARD"), List.of("平静"), List.of("平稳")));

        int defaultHits = 0;
        for (int i = 0; i < 1000; i++) {
            BgmCatalog.Pick pick = BgmCatalog.pick(tracks, null, "DATA_BOARD", "平静", "平稳", "seed-" + i);
            defaultHits += "default".equals(pick.track().key()) ? 1 : 0;
        }

        // 99:1 的权重，1000 次里高权重几乎全中；留 1% 的容错给哈希分布
        assertTrue(defaultHits >= 950, "高权重中签次数 " + defaultHits);
    }

    @Test
    @DisplayName("同一份冻结输入重复选曲得到同一首")
    void sameSeedSameTrack() {
        List<BgmCatalog.Track> tracks = List.of(
            track("a", 50, List.of("DATA_BOARD"), List.of("平静"), List.of("平稳")),
            track("b", 50, List.of("DATA_BOARD"), List.of("平静"), List.of("平稳")));

        String first = BgmCatalog.pick(tracks, null, "DATA_BOARD", "平静", "平稳", "frozen-input-json").track().key();
        for (int i = 0; i < 50; i++) {
            assertEquals(first,
                BgmCatalog.pick(tracks, null, "DATA_BOARD", "平静", "平稳", "frozen-input-json").track().key());
        }
    }

    @Test
    @DisplayName("没有线能用的曲目时不出配乐，并说明原因")
    void noEligibleTrack() {
        List<BgmCatalog.Track> tracks = List.of(
            track("knowledge", 100, List.of("KNOWLEDGE_EXPLAINER"), List.of(), List.of()));

        BgmCatalog.Pick pick = BgmCatalog.pick(tracks, null, "DATA_BOARD", null, null, "seed");

        assertNull(pick.track());
        assertTrue(pick.reason().contains("DATA_BOARD"));
    }

    @Test
    @DisplayName("空曲库不出配乐")
    void emptyCatalog() {
        BgmCatalog.Pick pick = BgmCatalog.pick(List.of(), null, "DATA_BOARD", "紧张", "紧凑", "seed");

        assertNull(pick.track());
        assertFalse(pick.reason().isBlank());
    }

    @Test
    @DisplayName("清单指向不存在的 mp3 时该条被跳过，不是整份清单作废")
    void skipsMissingFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("catalog.json"), """
            {"tracks": [
              {"key": "gone", "file": "gone.mp3", "weight": 100, "lines": ["DATA_BOARD"]},
              {"key": "real", "file": "real.mp3", "weight": 10, "lines": ["DATA_BOARD"]}
            ]}
            """);
        Files.writeString(dir.resolve("real.mp3"), "");

        List<BgmCatalog.Track> tracks = BgmCatalog.load(dir);

        assertEquals(1, tracks.size());
        assertEquals("real", tracks.get(0).key());
    }

    @Test
    @DisplayName("清单里的路径不能指到配乐目录外")
    void rejectsTraversal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("catalog.json"), """
            {"tracks": [
              {"key": "escape", "file": "../outside.mp3", "weight": 100},
              {"key": "slash", "file": "sub/x.mp3", "weight": 100}
            ]}
            """);

        assertTrue(BgmCatalog.load(dir).isEmpty());
    }

    @Test
    @DisplayName("清单写坏时退化成空曲库，不是抛异常")
    void brokenCatalog(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("catalog.json"), "{not json");

        assertTrue(BgmCatalog.load(dir).isEmpty());
    }

    @Test
    @DisplayName("故事计划缺失时按编辑线与权重选，不报错")
    void noStoryPlan() {
        List<BgmCatalog.Track> tracks = List.of(
            track("only", 100, List.of("DATA_BOARD"), List.of("紧张"), List.of("紧凑")));

        BgmCatalog.Pick pick = BgmCatalog.pick(tracks, null, "DATA_BOARD", null, null, "seed");

        assertNotNull(pick.track());
        assertEquals(0, pick.measured().get("affinity"));
    }

    /** 测试的工作目录是模块目录，曲库在仓库根下。 */
    private static Path repo() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("仓库里的真实曲库：清单可解析、每首 mp3 都在、四条线都选得出配乐")
    void realCatalogIsUsable() {
        Path dir = repo().resolve(Path.of("data", "audio", "bgm"));
        assumeTrue(Files.isDirectory(dir), "仓库里应当有配乐目录：" + dir);

        List<BgmCatalog.Track> tracks = BgmCatalog.load(dir);

        assertFalse(tracks.isEmpty(), "真实清单一条都没解析出来");
        for (BgmCatalog.Track track : tracks) {
            assertTrue(Files.isRegularFile(dir.resolve(track.file())), "清单里的 mp3 不存在：" + track.file());
            assertTrue(track.weight() >= 1 && track.weight() <= 100, "权重越界：" + track.key());
        }
        for (String line : List.of("DATA_BOARD", "TREND_INSIGHT", "KNOWLEDGE_EXPLAINER", "NEWS_EXPLAINER")) {
            BgmCatalog.Pick pick = BgmCatalog.pick(tracks, dir, line, "紧张", "紧凑", "seed");
            assertNotNull(pick.track(), "编辑线选不出配乐：" + line);
            // 选中的曲目必须能直接交给 ffmpeg：路径真实存在，不是只给了个文件名
            assertTrue(Files.isRegularFile(pick.file()), "选曲没有给出可用的文件路径：" + pick.file());
        }
    }

    @Test
    @DisplayName("真实曲库里默认曲目 we-never 权重最高，且在四条线上都可用")
    void realDefaultTrackHasTopWeight() {
        Path dir = repo().resolve(Path.of("data", "audio", "bgm"));
        assumeTrue(Files.isDirectory(dir), "仓库里应当有配乐目录：" + dir);

        List<BgmCatalog.Track> tracks = BgmCatalog.load(dir);
        BgmCatalog.Track top = tracks.stream()
            .max(Comparator.comparingInt(BgmCatalog.Track::weight)).orElseThrow();

        assertEquals("we-never", top.key(), "默认曲目应当是 we-never；换默认要先确认，不能悄悄改");
        for (String line : List.of("DATA_BOARD", "TREND_INSIGHT", "KNOWLEDGE_EXPLAINER", "NEWS_EXPLAINER")) {
            assertTrue(top.accepts(line), "默认曲目没覆盖编辑线：" + line);
        }
    }
}
