package org.dromara.heron.domain;

import java.util.List;

/**
 * 导演契约骨架（V2 六契约）：推理阶段产物，Java 校验后落 `data/render/<jobId>/`。
 *
 * <p>Schema 与校验规则见 `docs/plans/2026-11-26-video-director-contracts.md`。
 * 本批只落骨架（record + 字段），不接线不写管线逻辑——S3 起
 * {@code DirectorPipeline} 按五段十步逐契约填充与过闸。
 *
 * <p>复用契约不在此重造：机会/洞察沿 {@code OpportunityFacts} 系，
 * NarrativeTreatment 演进自 {@code StoryPlanContract} v2（story-plan/1）。
 */
final class DirectorContracts {

    private DirectorContracts() { }

    /** 一·VisualTreatment：一次结构化 LLM 调用产出的视觉方向（visual-treatment/1）。 */
    record VisualTreatment(String schemaVersion, String profileId, Integer profileVersion,
                           String visualMode, String visualStatement, List<String> lensLanguage,
                           String motionDiscipline, String shotLanguageNotes, String profileRefusalReason) { }

    /** 二·Storyboard：与 NarrativeTreatment.scenes 一一对应的镜头清单（storyboard/1）。 */
    record Storyboard(String schemaVersion, String narrativeRef, List<Shot> shots) { }

    /** Storyboard 单镜。durationBudgetSec 只做防过长预算，实际时长由 AudioTiming 实测接管。 */
    record Shot(String shotId, String storyPurpose, String visualIntent, List<String> techniques,
                DurationBudget durationBudgetSec, List<String> inputs, List<String> componentHints,
                String narrationText, String syncMode) { }

    /** 镜头预估旁白预算窗（秒）：min ≥ 3、max ≤ 15。 */
    record DurationBudget(double min, double max) { }

    /** 二·五·AudioTiming：TTS 前置测音接缝，单镜渲染的时长真源（audio-timing/1）。 */
    record AudioTiming(String schemaVersion, double totalDurSec, List<AudioShot> shots) { }

    /** AudioTiming 单镜：targetShotDurSec 是该镜 data-duration 与 __HERON_SHOT__.durationSec 的硬约束。 */
    record AudioShot(String shotId, double startSec, double measuredDurSec, double padTailSec,
                     double targetShotDurSec, List<WordAnchor> wordAnchors) { }

    /** 词级锚点：关键视觉变化必须落在这个时刻，音画同步与末拍稳定一次性成立。 */
    record WordAnchor(String text, double offsetSec) { }

    /** 三·EditDecisionList：成片时序与混音编排，Java 依 AudioTiming+Storyboard+RenderReport 生成（edl/1）。 */
    record EditDecisionList(String schemaVersion, int fps, Canvas canvas,
                            List<Track> tracks, Captions captions) { }

    /** 画幅声明；必须匹配 Profile 的 canvas。 */
    record Canvas(int width, int height) { }

    /** EDL 轨道：video 或 audio；clips 时序单调不重叠。 */
    record Track(String type, List<Clip> clips) { }

    /** EDL 片段：video 轨的 shotFile 必须实测时长匹配 durSec（偏差 ≤1 帧）。 */
    record Clip(String shotId, String shotFile, String source, double startSec, double durSec,
                String transitionIn, String transitionOut, Double duckDb, Double gainDb) { }

    /** V1 固定 engine=ffmpeg-ass：复用 SubtitleBurn 读 Profile tokens.css 烧录。 */
    record Captions(String vttRef, boolean burnIn, String engine, String style) { }

    /** 四·RenderReport：三层闸每镜一行，返工追加 attempt 不覆盖（render-report/1）。 */
    record RenderReport(String schemaVersion, String profileId, Integer profileVersion,
                        List<ShotReport> shots, Budget budget) { }

    /** RenderReport 单镜：finalVerdict ∈ pass / rework / giveup；giveup = 预算耗尽进人工。 */
    record ShotReport(String shotId, List<Attempt> attempts, String finalVerdict, Integer finalAttempt) { }

    /** 单次尝试的三层检查；vlm 仅在 static+quantified 全过后执行（省无效 VLM 费用）。 */
    record Attempt(int attempt, LayerCheck staticCheck, LayerCheck quantified, LayerCheck vlm,
                   String verdict, String reworkBrief) { }

    /** 一层检查结果：metrics 里放 maxStillSec / endStableFrames / flashCount 等硬判据实测。 */
    record LayerCheck(Boolean pass, java.util.Map<String, Object> metrics, List<String> issues) { }

    /** 预算：maxIters 与成本闸由 Java 注入，Agent 不可改。 */
    record Budget(int maxIters, long costCents, long costCapCents) { }
}
