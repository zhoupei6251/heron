package org.dromara.heron.service.impl.shared.media;

import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 声音提示的纯规划层：把已经校验的字幕场景窗映射为可混音的提示。
 *
 * <p>这里不估算镜头时长。转场使用下一个场景窗的起点，数字落点使用数字屏的起点，
 * 结论强调使用 {@code closing-slot} 的起点；调用方必须传入
 * {@link NewsNarrationTiming} 实际对齐后的窗口。</p>
 */
public final class SoundCue {

    /** 旁白内额外衰减 3 dB，加上基础衰减最多共 6 dB。 */
    static final double NARRATION_DUCK_DB = -3.0;

    private static final double GAP_GAIN_DB = -3.0;
    private static final double NARRATION_GAIN_DB = -6.0;
    private static final long MAX_NARRATION_OVERLAP_MS = 250;
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9_.:-]{1,100}");

    private SoundCue() { }

    /** 三类音效及其默认资产；资产均复用仓库现有 {@code data/audio/sfx}。 */
    public enum Type {
        TRANSITION_WHOOSH("whoosh-short.mp3"),
        NUMERIC_TICK("click.mp3"),
        CONCLUSION_EMPHASIS("chime.mp3");

        private final String assetName;

        Type(String assetName) {
            this.assetName = assetName;
        }

        /** 默认素材文件名。 */
        public String assetName() {
            return assetName;
        }
    }

    /**
     * 已校验的连续时间窗。
     *
     * @param sceneId 场景或句子的稳定 ID
     * @param startMs 包含的起点，毫秒
     * @param endMs   不包含的终点，毫秒
     */
    public record Window(String sceneId, long startMs, long endMs) {
        public Window {
            if (sceneId == null || !SAFE_ID.matcher(sceneId).matches()) {
                throw invalid("时间窗 ID 非法：" + sceneId);
            }
            if (startMs < 0 || endMs <= startMs) {
                throw invalid("时间窗 " + sceneId + " 的起点或终点非法");
            }
        }
    }

    /**
     * 一条待渲染音效。
     *
     * @param id      提示 ID
     * @param type    音效类型
     * @param sceneId 触发提示的场景 ID
     * @param atMs    实际切点或场景窗起点，毫秒
     * @param asset   已存在的素材文件
     */
    public record Cue(String id, Type type, String sceneId, long atMs, Path asset) {
        public Cue {
            if (id == null || id.isBlank() || type == null || sceneId == null || sceneId.isBlank()
                || atMs < 0 || asset == null) {
                throw invalid("音效提示字段不完整");
            }
        }
    }

    /** 可序列化到 checks 的显式结果；SKIP 已处理但不代表音效已渲染。 */
    public record Check(String status, String code, Type type, String sceneId, String asset, String reason) {
        public Check {
            if (!"PASS".equals(status) && !"SKIP".equals(status)) {
                throw invalid("音效 check 状态非法：" + status);
            }
            if ("SKIP".equals(status) && (code == null || code.isBlank() || reason == null || reason.isBlank())) {
                throw invalid("音效跳过必须带原因与代码");
            }
        }

        /** true 表示已处理；SKIP 的 measured.rendered=false，不代表素材已渲染。 */
        public boolean passed() {
            return "PASS".equals(status) || "SKIP".equals(status);
        }

        /** 适配 Gate；显式 SKIP 非阻断，但保留状态、代码、原因与未渲染指标。 */
        public Map<String, Object> toMap() {
            boolean passed = passed();
            boolean skipped = "SKIP".equals(status);
            String typeName = type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT);
            Map<String, Object> measured = new LinkedHashMap<>();
            measured.put("skipped", skipped);
            measured.put("rendered", !skipped);
            measured.put("missingAsset", "MISSING_SFX_ASSET".equals(code));
            measured.put("outsideTimeline", "SFX_OUTSIDE_TIMELINE".equals(code));
            measured.put("transitionWhoosh", type == Type.TRANSITION_WHOOSH);
            measured.put("numericTick", type == Type.NUMERIC_TICK);
            measured.put("conclusionEmphasis", type == Type.CONCLUSION_EMPHASIS);

            Map<String, Object> value = new LinkedHashMap<>();
            value.put("gate", "sfx");
            value.put("status", status);
            value.put("code", code);
            value.put("inputLocator", "sfx:" + typeName + ":" + sceneId + ":" + asset);
            value.put("passed", passed);
            value.put("reason", reason);
            value.put("measured", measured);
            return value;
        }
    }

    /** 规划结果；没有素材时 cues 为空，但 checks 一定非空。 */
    public record Plan(List<Cue> cues, List<Check> checks) {
        public Plan {
            cues = cues == null ? List.of() : List.copyOf(cues);
            checks = checks == null ? List.of() : List.copyOf(checks);
        }

        /** 是否至少有一条可渲染提示。 */
        public boolean hasCues() {
            return !cues.isEmpty();
        }
    }

    /**
     * 从实际场景窗规划三类提示。
     *
     * @param sceneWindows    连续的已校验场景窗，最后一项必须是 closing-slot
     * @param narrationWindows 实际字幕句窗，用于音效避让旁白；可为空
     * @param script          已校验脚本，数字只读 on_screen_text，缺省时回退 lead
     * @param sfxDirectory    音效目录
     * @return 提示与显式缺素材跳过记录
     */
    public static Plan plan(List<Window> sceneWindows, List<Window> narrationWindows, JsonNode script,
                            Path sfxDirectory) {
        validateScenes(sceneWindows, narrationWindows, script);
        List<Cue> cues = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        Set<Type> missing = new java.util.HashSet<>();

        //  Closing 入口只放结论强调，不再叠一层 whoosh；其余切点严格使用下一窗起点。
        for (int index = 1; index < sceneWindows.size() - 1; index++) {
            Window window = sceneWindows.get(index);
            add(cues, checks, missing, Type.TRANSITION_WHOOSH, window.sceneId(), window.startMs(), sfxDirectory);
        }
        JsonNode scenes = script.path("scenes");
        for (int index = 0; index < scenes.size(); index++) {
            JsonNode scene = scenes.get(index);
            JsonNode screenText = scene.path("on_screen_text");
            String text = screenText.isMissingNode() ? scene.path("lead").asString("") : screenText.asString("");
            if (DIGIT.matcher(text).find()) {
                Window window = sceneWindows.get(index);
                add(cues, checks, missing, Type.NUMERIC_TICK, window.sceneId(), window.startMs(), sfxDirectory);
            }
        }
        Window closing = sceneWindows.getLast();
        add(cues, checks, missing, Type.CONCLUSION_EMPHASIS, closing.sceneId(), closing.startMs(), sfxDirectory);
        return new Plan(cues, checks);
    }

    private static void add(List<Cue> cues, List<Check> checks, Set<Type> missing, Type type, String sceneId,
                            long atMs, Path directory) {
        Path asset = directory == null ? null : directory.resolve(type.assetName());
        if (asset == null || !Files.isRegularFile(asset) || !Files.isReadable(asset)) {
            if (missing.add(type)) {
                checks.add(new Check("SKIP", "MISSING_SFX_ASSET", type, sceneId, type.assetName(),
                    "音效素材缺失或不可读，" + type.name() + " 已显式跳过"));
            }
            return;
        }
        cues.add(new Cue(type.name() + ":" + sceneId, type, sceneId, atMs, asset.toAbsolutePath().normalize()));
    }

    private static void validateScenes(List<Window> scenes, List<Window> narration, JsonNode script) {
        if (scenes == null || scenes.size() < 2 || scenes.getFirst().startMs() != 0
            || !"closing-slot".equals(scenes.getLast().sceneId())) {
            throw invalid("场景窗必须从 0 开始且以 closing-slot 结束");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < scenes.size(); index++) {
            Window window = scenes.get(index);
            if (!ids.add(window.sceneId())) {
                throw invalid("场景窗 ID 重复：" + window.sceneId());
            }
            if (index > 0 && window.startMs() != scenes.get(index - 1).endMs()) {
                throw invalid("场景窗不连续，拒绝估算切点：" + window.sceneId());
            }
        }
        if (script == null || !script.path("scenes").isArray()
            || script.path("scenes").size() != scenes.size() - 1) {
            throw invalid("脚本场景数与已校验场景窗不一致");
        }
        if (narration == null) {
            throw invalid("旁白时间窗不能为 null");
        }
        long previousStart = -1;
        long previousEnd = -1;
        long totalEnd = scenes.getLast().endMs();
        for (Window window : narration) {
            if (window.endMs() > totalEnd || window.startMs() < previousStart
                || window.startMs() < previousEnd - MAX_NARRATION_OVERLAP_MS) {
                throw invalid("旁白时间窗越界、倒退或重叠超过 250ms：" + window.sceneId());
            }
            previousStart = window.startMs();
            previousEnd = window.endMs();
        }
    }

    static double gapGainDb() {
        return GAP_GAIN_DB;
    }

    static double narrationGainDb() {
        return NARRATION_GAIN_DB;
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(409, "news_sound_cue_alignment", reason);
    }
}
