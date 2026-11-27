package org.dromara.heron.service.impl.lane.databoard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronDatasetUsageMapper;
import org.dromara.heron.service.impl.shared.support.ModelJson;
import org.dromara.heron.service.impl.shared.support.OpenAiModelClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据看板线的「策划」：在写标题与写旁白之前，先把这片子讲的是什么事定下来。
 *
 * <p>产出是一份 {@link EditorialStory}（story/1）。它随冻结快照落盘，生产期再按同一份故事
 * 复核与写稿，所以「策划说什么」和「片子讲什么」能逐条对得上。
 *
 * <p>三条铁律：
 * <ul>
 *   <li><b>模型不写数字</b>——故事里只能用 {@code {{facts.*}}} 占位符，数值唯一来源是快照；</li>
 *   <li><b>前提由机器算</b>——叙事类型、张力类型、可用引用都在 Java 侧算好，模型只能在允许集合里选；</li>
 *   <li><b>没故事就不做</b>——策划不通过就换下一条候选，全不通过则这条线本轮不出片。</li>
 * </ul>
 *
 * <p>拒绝理由用固定码，便于按「哪条闸 + 哪段输入 + 为什么拒」回答：
 * 数据层面的闸在调模型之前判（不新鲜、太稀、幅度不可信、许可、没强信号、重复、类别不参与），
 * 故事层面的闸在模型输出之后判（只能靠假设、要求跨源、说不出谁受影响、契约不过）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasetStoryPlanner {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/data-board/story-contract.md");
    private static final String STAGE = "dataset-story";
    /** 与 {@link DatasetRouter} 的 7 天去重同口径：同源最近出过片就不重复讲同一份数据。 */
    private static final int DEDUP_DAYS = 7;
    /** 覆盖率低于此值说明取数被截断，故事会建在残缺数据上。 */
    private static final double MIN_COVERAGE = 0.5;

    /** 策划取数用的点数：走机会时与机会当时的取数口径一致，其余走生产兜底口径。 */
    public static final int DEFAULT_POINTS = 30;

    private final OpenAiModelClient model;
    private final DatasetFetchService datasets;
    private final HeronDatasetUsageMapper usageMapper;
    private final HeronDataSourceMapper dataSourceMapper;

    /**
     * 一次策划的结果：要么有故事，要么有明确的拒绝码。
     *
     * @param story        策划结论；被拒时为 null
     * @param rejectCode   拒绝码；通过时为 null
     * @param rejectReason 拒绝理由；通过时为 null
     */
    public record Result(EditorialStory story, String rejectCode, String rejectReason) {

        /** 有故事、无拒绝码的结果。 */
        public static Result accepted(EditorialStory story) {
            return new Result(story, null, null);
        }

        /** 有拒绝码、无故事的结果。 */
        public static Result rejected(String code, String reason) {
            return new Result(null, code, reason);
        }

        /** 是否策划出了故事；false 表示带拒绝码。 */
        public boolean accepted() {
            return story != null;
        }
    }

    /**
     * 做一次策划。
     *
     * @param source        数据源；提供类别口径与许可信息
     * @param points        规划取数的点数，必须与生产取数同口径，否则引用解析不到
     * @param sourceKind    选题来源：opportunity / router / digest
     * @param opportunityId 机会 id；来源为机会时必填
     * @param headline      选题头线，只作背景
     * @param signalsJson   机器信号 JSON，只作背景
     */
    public Result plan(ProductionRunVo run, HeronDataSource source, int points, String sourceKind,
                       Long opportunityId, String headline, String signalsJson) {
        if (source == null) {
            return Result.rejected("story_source_missing", "找不到数据源");
        }
        String sourceKey = source.getSourceKey();
        DatasetSignal.CategoryPolicy policy = DatasetSignal.CategoryPolicy.of(source.getCategory());

        JsonNode snapshot;
        try {
            // 规划取数：与生产同口径，但不落产物、不写 last_updated，也不占 7 天去重窗口
            snapshot = datasets.snapshot(sourceKey, points);
        } catch (RuntimeException e) {
            log.warn("故事规划取数失败 source={} cause={}", sourceKey, e.getMessage());
            return Result.rejected("story_fetch_failed", "规划取数失败：" + e.getMessage());
        }
        if (snapshot == null || !snapshot.isObject()) {
            return Result.rejected("story_fetch_failed", "规划取数没有拿到快照");
        }

        String shape = snapshot.path("shape").asString("");
        String cadence = snapshot.path("cadence").asString(source.getCadence());
        YearMonth today = YearMonth.now();
        boolean geo = "geo".equals(shape);
        DatasetSignal.Signal signal = geo ? null : DatasetSignal.raw(snapshot, points, policy);

        // 1) 数据不新鲜
        if (signal != null && DatasetSignal.stale(signal.periodEnd(), cadence, today)) {
            return Result.rejected("story_data_stale", "数据末点 " + signal.periodEnd() + " 已过期");
        }
        // 2) 点数太少或覆盖太低
        int pointCount = signal != null ? signal.points() : rowsOf(snapshot);
        double coverage = signal != null ? signal.coverage() : 1.0;
        if (pointCount < DatasetSignal.MIN_BASELINE_POINTS || coverage < MIN_COVERAGE) {
            return Result.rejected("story_data_thin", "点数 " + pointCount + "、覆盖 " + coverage);
        }
        // 3) 幅度不可信
        if (signal != null && signal.changePct() != null
            && Math.abs(signal.changePct()) > DatasetSignal.MAX_PLAUSIBLE_CHANGE) {
            return Result.rejected("story_implausible_change", "变化幅度不可信：" + signal.changePct());
        }
        // 4) 许可不允许商用
        String commercialOk = snapshot.path("license").path("commercial_ok").asString("");
        if ("false".equals(commercialOk)) {
            return Result.rejected("story_license_blocked", "该源不允许商用");
        }
        // 5) 没有任何强信号
        if (!hasStrongSignal(signal, geo)) {
            return Result.rejected("story_signal_weak", "没有 z-score、没有新极值、变化也没到这个类别的大变化基准");
        }
        // 6) 7 天内同源出过片
        Set<String> recent = usageMapper.recentSourceKeys(LocalDateTime.now().minusDays(DEDUP_DAYS));
        if (recent.contains(sourceKey)) {
            return Result.rejected("story_duplicate", "7 天内用过该源：" + sourceKey);
        }
        // 9) 类别不参与选题
        if (!policy.eligible()) {
            return Result.rejected("story_category_ineligible", "该类别不参与选题：" + source.getCategory());
        }

        Set<String> narratives = DatasetStoryContract.availableNarratives(snapshot, signal);
        if (narratives.isEmpty()) {
            return Result.rejected("story_signal_weak", "这份数据撑不起任何一类叙事");
        }

        String reply = model.chat(run, STAGE, SYSTEM,
            prompt(snapshot, narratives, signal, source, sourceKind, headline, signalsJson));
        JsonNode node;
        try {
            node = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            return Result.rejected("story_contract", "模型输出不是 JSON");
        }
        EditorialStory parsed = EditorialStory.fromJson(node);
        if (parsed == null) {
            return Result.rejected("story_contract", "模型输出不是 JSON 对象");
        }
        // 策划依据由机器填：模型自述的信号、方向与哈希一律不作数
        EditorialStory story = parsed.withBasis(
            basis(snapshot, signal, geo, sourceKind, opportunityId));

        // 叙事类型的前提由机器算，模型不能自选一个撑不起来的故事
        if (!narratives.contains(story.narrative())) {
            return Result.rejected("story_contract",
                "叙事类型 " + story.narrative() + " 在这份数据里没有机器前提（可用：" + String.join("、", narratives) + "）");
        }

        // 7) 只能靠假设才成立
        if (story.factClaims().size() < DatasetStoryContract.MIN_FACT_CLAIMS) {
            return Result.rejected("story_all_assumption",
                "事实主张只有 " + story.factClaims().size() + " 条，不足 " + DatasetStoryContract.MIN_FACT_CLAIMS + " 条");
        }
        // 8) 必须跨源比较才成立
        String otherSource = foreignSource(story, sourceKey);
        if (otherSource != null) {
            return Result.rejected("story_needs_multi_source", "故事引用了别的源：" + otherSource);
        }
        // 10) 说不出谁受影响
        if (story.stakes() == null || story.stakes().audience() == null
            || story.stakes().audience().isBlank()) {
            return Result.rejected("story_no_stakes", "stakes 为空");
        }
        // 11) 硬校验不过
        List<String> problems = DatasetStoryContract.violations(story, snapshot);
        if (!problems.isEmpty()) {
            return Result.rejected("story_contract", String.join("；", problems));
        }
        return Result.accepted(story);
    }

    /** 强信号：z-score、新极值、大变化三者有其一即可，三者皆无就是没有故事。 */
    private static boolean hasStrongSignal(DatasetSignal.Signal signal, boolean geo) {
        if (signal == null) {
            // geo 没有时间轴也没有变化，分布本身就是它的强信号
            return geo;
        }
        if (signal.zscore() != null || signal.extreme()) {
            return true;
        }
        Double change = signal.changePct();
        double scale = signal.changeScale();
        return change != null && scale > 0 && Math.abs(change) >= scale;
    }

    /** 机器填的策划依据。 */
    private static EditorialStory.Basis basis(JsonNode snapshot, DatasetSignal.Signal signal, boolean geo,
                                               String sourceKind, Long opportunityId) {
        return new EditorialStory.Basis(
            signal == null ? (geo ? "geo" : snapshot.path("shape").asString("")) : signal.signalType(),
            signal == null ? null : signal.changePct(),
            signal == null ? null : signal.zscore(),
            signal != null && signal.extreme(),
            signal == null ? 1.0 : signal.coverage(),
            signal == null ? rowsOf(snapshot) : signal.points(),
            signal == null ? null : signal.periodStart(),
            signal == null ? null : signal.periodEnd(),
            snapshot.path("data_sha256").asString(""),
            sourceKind,
            opportunityId);
    }

    /**
     * 故事里是否提到了别的数据源。
     *
     * <p>dataset/1 一份快照只来自一个 source_key，跨源比较讲不成立——所以只要故事文本里
     * 出现了另一个源的标识，就说明这条故事依赖跨源，直接拒。
     */
    private String foreignSource(EditorialStory story, String sourceKey) {
        Set<String> others = new LinkedHashSet<>();
        for (HeronDataSource item : dataSourceMapper.enabledSources()) {
            String key = item.getSourceKey();
            if (key != null && !key.equals(sourceKey)) {
                others.add(key);
            }
        }
        if (others.isEmpty()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        texts.add(story.oneLine());
        if (story.tension() != null) {
            texts.add(story.tension().statement());
        }
        for (EditorialStory.Claim claim : story.claims()) {
            texts.add(claim.text());
        }
        if (story.payoff() != null) {
            texts.add(story.payoff().text());
        }
        if (story.stakes() != null) {
            texts.add(story.stakes().audience());
            texts.add(story.stakes().impact());
        }
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (String other : others) {
                if (text.contains(other)) {
                    return other;
                }
            }
        }
        return null;
    }

    private static int rowsOf(JsonNode snapshot) {
        JsonNode rows = snapshot.path("rows");
        return rows.isArray() ? rows.size() : 0;
    }

    /** 提示词：给规划快照、机器前提与允许集合；数字只留在快照里。 */
    private static String prompt(JsonNode snapshot, Set<String> narratives, DatasetSignal.Signal signal,
                                 HeronDataSource source, String sourceKind, String headline, String signalsJson) {
        StringBuilder text = new StringBuilder()
            .append("数据源：").append(source.getSourceKey()).append(' ').append(source.getName())
            .append("（栏目来源类别=").append(source.getCategory()).append("，节奏=").append(source.getCadence()).append("）\n")
            .append("选题来源：").append(sourceKind).append('\n');
        if (headline != null && !headline.isBlank()) {
            text.append("选题头线（只作背景，不得写进故事）：").append(headline).append('\n');
        }
        if (signalsJson != null && !signalsJson.isBlank()) {
            text.append("机器信号（只作背景，不得写进故事）：").append(signalsJson).append('\n');
        }
        text.append("可用叙事类型（只能从中选一个）：").append(String.join("、", narratives)).append('\n');
        text.append("机器前提：");
        if (signal == null) {
            text.append("形态=").append(snapshot.path("shape").asString(""))
                .append("，无时间轴，只能讲分布反差或问题—证据");
        } else {
            text.append("signal_type=").append(signal.signalType())
                .append("，change_pct=").append(signal.changePct())
                .append("，zscore=").append(signal.zscore())
                .append("，extreme=").append(signal.extreme())
                .append("，coverage=").append(signal.coverage())
                .append("，points=").append(signal.points())
                .append("，区间=").append(signal.periodStart()).append('~').append(signal.periodEnd());
        }
        text.append('\n')
            .append("规划快照（数字只在这里；故事里任何数值都必须写成 {{facts.<路径>}} 占位符）：\n")
            .append(snapshot.toString())
            .append("\n按契约输出 story JSON。basis 由系统填写，不用输出。");
        return text.toString();
    }

    private static String resource(String path) {
        try (InputStream stream = DatasetStoryPlanner.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
