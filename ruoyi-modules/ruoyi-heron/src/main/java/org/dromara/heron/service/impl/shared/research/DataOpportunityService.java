package org.dromara.heron.service.impl.shared.research;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.vo.DataOpportunityVo;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.service.impl.lane.databoard.Column;
import org.dromara.heron.service.impl.lane.databoard.DatasetFetchService;
import org.dromara.heron.service.impl.lane.databoard.DatasetSignal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据机会：把注册表里的公开数据逐源算一遍信号，够强的落成机会。
 *
 * <p>第一步只产「机会」本身，不接入总编辑、不接入生产。目的是拿真实数据看
 * 「算出来的东西像不像可做的选题」，再据此校准阈值。
 *
 * <p>不调模型：标题按信号拼，数字全部来自快照。模型参与表述留到第二步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataOpportunityService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 分析窗口按点算；与 cadence 一起才是完整口径。 */
    static final int WINDOW_POINTS = 120;
    /** 低于这个分不进榜：宁可少几条，也不要 119 条噪声。 */
    public static final double THRESHOLD = 50.0;
    private static final int MAX_FAILURES = 10;
    /** 快照里最多留多少点：与分析窗口同宽，够复核，又不至于把整条序列塞进数据库。 */
    public static final int SNAPSHOT_POINTS = WINDOW_POINTS;
    /** 单条快照的字符上限；超了只留元信息，宁可不全，也不要一行占几十兆。 */
    static final int MAX_SNAPSHOT_CHARS = 64_000;
    public static final String STATUS_NEW = "new";
    static final String STATUS_REJECTED = "rejected";

    private final HeronDataSourceMapper dataSourceMapper;
    private final HeronDataOpportunityMapper opportunityMapper;
    private final DatasetFetchService datasets;
    private final ObjectProvider<Clock> clocks;

    /**
     * 一轮分析的统计。
     *
     * @param sources    本轮参与分析的启用源数量
     * @param analyzed   拿到数据并算出信号的数量
     * @param mergedAway 过线但被合并进同义机会的条数
     * @param created    新建机会数
     * @param updated    命中已有机会并更新的条数
     * @param skipped    这个源算不出信号（形态不支持、点数不足、数据过期、幅度不可信）
     * @param failed     取数或算信号抛异常的源数量
     * @param failures   失败源摘要（sourceKey: 原因），最多保留 10 条
     * @param unhealthy  跑完后探测状态不是 ok 的启用源数量
     */
    public record Report(int sources, int analyzed, int mergedAway, int created, int updated,
                         int skipped, int failed, int unhealthy, List<String> failures) { }

    /** 用默认分析窗口（{@value #WINDOW_POINTS} 个点）分析全部启用数据源。 */
    public Report analyze() {
        return analyze(WINDOW_POINTS);
    }

    /**
     * 逐源取快照、算信号，分数低于阈值的丢弃，同一件事的多个源合并后 upsert 成机会；
     * 单个源失败只计入 failed/failures，不中断整轮。分析同时就是探测：被 blocked 的源在这里取数成功即可恢复。
     *
     * @param windowPoints 请求的数据点数；≤0 时退回默认 {@value #WINDOW_POINTS}
     * @return 本轮统计
     */
    public Report analyze(int windowPoints) {
        int points = windowPoints <= 0 ? WINDOW_POINTS : windowPoints;
        // 分析就是探测：所有启用源都试一次，被 blocked 的源只有在这里成功取到数才能恢复
        List<HeronDataSource> sources = dataSourceMapper.enabledSources();
        int analyzed = 0;
        int skipped = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        java.time.YearMonth today = java.time.YearMonth.from(now());
        for (HeronDataSource source : sources) {
            try {
                JsonNode snapshot = datasets.snapshot(source.getSourceKey(), points);
                DatasetSignal.Signal signal = DatasetSignal.of(snapshot, points, today,
                    DatasetSignal.CategoryPolicy.of(source.getCategory()));
                if (signal == null) {
                    skipped++;
                    continue;
                }
                analyzed++;
                int weight = Column.forSource(source.getSourceKey()).weight();
                double score = DatasetSignal.score(signal, weight);
                if (score < THRESHOLD) {
                    continue;
                }
                candidates.add(new Candidate(source.getSourceKey(), source.getName(), source.getCadence(),
                    snapshot.path("metric").path("unit").asString(""),
                    Column.forSource(source.getSourceKey()),
                    signal, score,
                    DatasetSignal.headline(signal, source.getName(), source.getCadence()),
                    snapshotForStore(snapshot)));
            } catch (RuntimeException e) {
                failed++;
                if (failures.size() < MAX_FAILURES) {
                    failures.add(source.getSourceKey() + ": " + e.getMessage());
                }
                log.warn("数据机会分析失败 source={} cause={}", source.getSourceKey(), e.getMessage());
            }
        }
        List<Merged> merged = merge(candidates);
        int created = 0;
        int updated = 0;
        for (Merged group : merged) {
            if (upsert(group)) {
                created++;
            } else {
                updated++;
            }
        }
        // 跑完再数一次：探测状态是这一轮的结果，不是开跑前的快照
        int unhealthy = (int) dataSourceMapper.enabledSources().stream()
            .filter(source -> !"ok".equals(source.getProbeStatus()))
            .count();
        return new Report(sources.size(), analyzed, candidates.size() - merged.size(),
            created, updated, skipped, failed, unhealthy, failures);
    }

    /**
     * 一个源 vs 一份数据的候选，还没落库。
     *
     * @param unit 计量单位；与栏目、方向、信号类型、窗口一起决定「是不是同一件事」
     */
    public record Candidate(String sourceKey, String sourceName, String cadence, String unit, Column column,
                     DatasetSignal.Signal signal, double score, String headline, String snapshotJson) { }

    /** 合并后的一组：{@code lead} 是分数最高的一条，其余只留源标识。 */
    public record Merged(Candidate lead, List<String> members) {
        public int count() {
            return members.size() + 1;
        }
    }

    /**
     * 同一件事的多个侧面只留一条。
     *
     * <p>三个 USGS 震级门槛同向是同一份计数，这类「同一量的多个入口」才是合并对象。
     * 判据是「同栏目 + 同计量单位 + 同实体 + 同指标 + 同方向 + 同信号类型 + 同窗口」。
     *
     * <p>曾经的判据里没有实体与指标，于是七种人民币交叉盘被并成一条「澳元兑人民币」，
     * 标题还写着「同向 7 个源」——用欧元跌去佐证澳元跌，是把不同实体当成互相印证。
     *
     * <p>被合并的源不丢弃：写进机会的 {@code merged_sources}，审计时能还原。
     */
    public static List<Merged> merge(List<Candidate> candidates) {
        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            // 实体与指标必须同：不同货币对、不同实体的同向变化是两件事，
            // 并在一起会把「欧元跌」当成「澳元跌」的佐证
            String key = candidate.column().key()
                + '|' + candidate.unit()
                + '|' + candidate.signal().entity()
                + '|' + candidate.signal().metric()
                + '|' + direction(candidate.signal())
                + '|' + candidate.signal().signalType()
                + '|' + candidate.signal().windowPoints();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        List<Merged> merged = new ArrayList<>();
        for (List<Candidate> group : groups.values()) {
            group.sort(Comparator.comparingDouble(Candidate::score).reversed());
            Candidate lead = group.get(0);
            List<String> members = group.stream().skip(1).map(Candidate::sourceKey).toList();
            merged.add(new Merged(lead, members));
        }
        return merged;
    }

    /** 合并只认同向：涨跌相反的两个源讲的是两件事。 */
    static String direction(DatasetSignal.Signal signal) {
        Double change = signal.changePct();
        if (change == null || Math.abs(change) < 1e-9) {
            return "flat";
        }
        return change > 0 ? "up" : "down";
    }

    /** @return true 表示新建，false 表示更新当天已有机会 */
    private boolean upsert(Merged group) {
        Candidate lead = group.lead();
        DatasetSignal.Signal signal = lead.signal();
        LocalDateTime now = now();
        String dedupKey = dedupKey(lead.sourceKey(), signal, now.toLocalDate());
        BigDecimal scoreValue = BigDecimal.valueOf(lead.score()).setScale(2, RoundingMode.HALF_UP);
        String signals = signalsJson(signal, group.members());
        String headline = headlineWithMerged(lead.headline(), group.count());
        HeronDataOpportunity existing = opportunityMapper.selectOne(QueryBuilder
            .lambda(HeronDataOpportunity.class).eq(HeronDataOpportunity::getDedupKey, dedupKey)
            .last("LIMIT 1").build());
        if (existing != null) {
            // 同一天重算：分数与信号刷新，人工淘汰的结论保留，不因为重跑又冒出来
            opportunityMapper.lambda()
                .set(HeronDataOpportunity::getScore, scoreValue)
                .set(HeronDataOpportunity::getSignals, signals)
                .set(HeronDataOpportunity::getHeadline, headline)
                .set(HeronDataOpportunity::getPoints, signal.points())
                .set(HeronDataOpportunity::getPeriodEnd, signal.periodEnd())
                .set(HeronDataOpportunity::getSnapshotJson, lead.snapshotJson())
                .set(HeronDataOpportunity::getObservedAt, now)
                .set(HeronDataOpportunity::getUpdateTime, now)
                .eq(HeronDataOpportunity::getId, existing.getId())
                .updateCount();
            return false;
        }
        HeronDataOpportunity row = new HeronDataOpportunity();
        row.setId(IdUtil.getSnowflakeNextId());
        row.setSourceKey(lead.sourceKey());
        row.setEntity(signal.entity());
        row.setMetric(signal.metric());
        row.setShape(signal.shape());
        row.setCadence(lead.cadence());
        row.setWindowPoints(signal.windowPoints());
        row.setPoints(signal.points());
        row.setPeriodStart(signal.periodStart());
        row.setPeriodEnd(signal.periodEnd());
        row.setScore(scoreValue);
        row.setSignals(signals);
        row.setSnapshotJson(lead.snapshotJson());
        row.setHeadline(headline);
        row.setStatus(STATUS_NEW);
        row.setDedupKey(dedupKey);
        row.setObservedAt(now);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        opportunityMapper.insert(row);
        return true;
    }

    /** 合并过的机会在标题里点明「同向几个源」，否则看着像漏掉了其余几条。 */
    public static String headlineWithMerged(String headline, int count) {
        if (count <= 1) {
            return headline;
        }
        String suffix = "（同向 " + count + " 个源）";
        int room = Math.max(0, 200 - suffix.length());
        String base = headline.length() > room ? headline.substring(0, room) : headline;
        return base + suffix;
    }

    /** 源 + 信号类型 + 实体 + 窗口 + 日期：同一源同一天只留一行。 */
    public static String dedupKey(String sourceKey, DatasetSignal.Signal signal, LocalDate day) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(
            sourceKey + "|" + signal.dedupPart() + "|" + day);
    }

    /**
     * 存进机会的数据快照：形状与元信息全留，点只留尾部 {@link #SNAPSHOT_POINTS} 个。
     *
     * <p>为什么必须存：源会变。实测过同一天里同一条源先返回 65 个点、几十分钟后返回 0 个点，
     * 只存派生信号的话，这条机会此后再也无人能验。
     *
     * <p>超长时连点都不留，只写元信息并注明——宁可标记「不全」，也不要写一行几十兆的库。
     */
    public static String snapshotForStore(JsonNode snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", snapshot.path("contract").asString(""));
        out.put("shape", snapshot.path("shape").asString(""));
        out.put("cadence", snapshot.path("cadence").asString(""));
        out.put("retrieved_at", snapshot.path("retrieved_at").asString(""));
        out.put("data_sha256", snapshot.path("data_sha256").asString(""));
        // 数据点在 rows 下（DatasetSignal 也读这里），不是 points
        JsonNode rows = snapshot.path("rows");
        int size = rows.isArray() ? rows.size() : 0;
        int from = Math.max(0, size - SNAPSHOT_POINTS);
        List<String> tail = new ArrayList<>();
        for (int i = from; i < size; i++) {
            tail.add(rows.get(i).toString());
        }
        out.put("source_points", size);
        out.put("truncated", from > 0);
        out.put("points", tail);
        String json = JSON.writeValueAsString(out);
        if (json.length() > MAX_SNAPSHOT_CHARS) {
            out.remove("points");
            out.put("points_dropped", "超过 " + MAX_SNAPSHOT_CHARS + " 字符，只保留元信息");
            json = JSON.writeValueAsString(out);
        }
        return json;
    }

    public static String signalsJson(DatasetSignal.Signal signal, List<String> members) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("signal_type", signal.signalType());
        node.put("magnitude", signal.magnitude());
        node.put("change_pct", signal.changePct());
        node.put("change_abs", signal.absChange());
        node.put("change_scale", signal.changeScale());
        node.put("zscore", signal.zscore());
        node.put("extreme", signal.extreme());
        node.put("points", signal.points());
        node.put("window_points", signal.windowPoints());
        node.put("coverage", signal.coverage());
        node.put("flags", signal.flags());
        node.put("merged_count", members.size() + 1);
        node.put("merged_sources", members);
        return JSON.writeValueAsString(node);
    }

    /**
     * 分页查机会，状态与源键都是可选过滤；按观察时间、分数倒序。
     *
     * @param status    机会状态；空则不过滤
     * @param sourceKey 数据源键；空则不过滤
     * @return 分页机会视图
     */
    public PageResult<DataOpportunityVo> list(String status, String sourceKey, PageQuery pageQuery) {
        var page = opportunityMapper.selectVoPage(pageQuery.build(), QueryBuilder
            .lambda(HeronDataOpportunity.class)
            .eq(status != null && !status.isBlank(), HeronDataOpportunity::getStatus, status)
            .eq(sourceKey != null && !sourceKey.isBlank(), HeronDataOpportunity::getSourceKey, sourceKey)
            .orderByDesc(HeronDataOpportunity::getObservedAt, HeronDataOpportunity::getScore)
            .build());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /** 人工淘汰：只改状态，不删行——「为什么不做」本身是要留的证据。 */
    public void reject(long opportunityId, String reason) {
        int changed = opportunityMapper.lambda()
            .set(HeronDataOpportunity::getStatus, STATUS_REJECTED)
            .set(HeronDataOpportunity::getRejectReason, reason == null ? "" : reason)
            .set(HeronDataOpportunity::getUpdateTime, now())
            .eq(HeronDataOpportunity::getId, opportunityId)
            .updateCount();
        if (changed != 1) {
            throw new org.dromara.heron.domain.HeronBusinessException(404, "opportunity_missing",
                "机会不存在：" + opportunityId);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clocks.getIfAvailable(Clock::systemUTC));
    }
}
