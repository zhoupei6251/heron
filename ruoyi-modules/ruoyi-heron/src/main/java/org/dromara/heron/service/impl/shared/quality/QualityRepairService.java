package org.dromara.heron.service.impl.shared.quality;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.EditorialOverride;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.FrozenContext;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.NewsFileVo;
import org.dromara.heron.domain.vo.ProductionArtifactVo;
import org.dromara.heron.domain.vo.ProductionReceiptVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.IProductionService;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.impl.lane.databoard.DatasetScriptAuthor;
import org.dromara.heron.service.impl.lane.databoard.DatasetScriptContract;
import org.dromara.heron.service.impl.lane.knowledge.KnowledgeScriptAuthor;
import org.dromara.heron.service.impl.lane.news.NewsScriptContract;
import org.dromara.heron.service.impl.lane.trend.TrendContract;
import org.dromara.heron.service.impl.shared.production.NewsProductionDomain;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审稿修复的执行环：拿报告 → 定向改稿 → 重跑整条流水线 → 复检 → 不过则回滚。
 *
 * <p>为什么是「重生产一个版本」而不是就地打补丁：改一句旁白会改掉音轨、cue 边界与画面，
 * 只改 JSON 不重渲，报告结论会和成片脱节——比不修复更糟。所以修复稿冻结进新版本的输入快照，
 * 由 {@link ProductionRunner} 跑同一条流水线，不在这一层另造渲染路径。
 *
 * <p>修复稿能不能进流水线，取决于写稿阶段读不读冻结快照里的 {@code editorial.qualityRepair}。
 * 这一层不做假设：复检先比稿子指纹，指纹不对就记 {@code repair_not_rechecked}，
 * 绝不把「没复检」写成「修好了」。
 *
 * <p>两种结果都落在质量报告里：过闸记 {@code repair.rechecked=true} 并补一条通过的 repair 闸；
 * 不过闸把 current_version_id 指回修复前版本，记 {@code repair_rolled_back} 并把新版本标为不通过。
 * 回滚不是删掉新版：新版留在历史里，人能看见它为什么没过。
 */
@Slf4j
@Service
public class QualityRepairService {

    /** 冻结快照里修复指令的位置：editorial.qualityRepair。 */
    static final String REPAIR_KEY = "qualityRepair";
    static final String NOTE_ROLLED_BACK = "repair_rolled_back";
    static final String NOTE_NOT_RECHECKED = "repair_not_rechecked";
    static final String GATE_REPAIR = "repair";

    /**
     * 扫描开关，默认关。
     *
     * <p>打开的前提是写稿阶段已经读回修复稿；否则每次修复都会白跑一条完整流水线，
     * 复检随后判「修复稿未被采用」并留痕，成本却是真实的。
     */
    private static final String ENABLED_KEY = "heron.news.quality-repair-enabled";
    private static final int POLL_SECONDS = 120;
    private static final int SCAN_LIMIT = 10;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronVideoVersionMapper versionMapper;
    private final HeronNewsWorkMapper workMapper;
    private final IProductionService production;
    private final NewsProductionDomain domain;
    private final NewsScriptAuthor author;
    private final KnowledgeScriptAuthor knowledgeAuthor;
    private final DatasetScriptAuthor datasetAuthor;
    private final Environment environment;
    private final ScheduledExecutorService scheduler;

    /** 本进程内已经尝试过的版本：同一版本只试一次，失败了也不在下一轮再烧一条流水线。 */
    private final Set<Long> attempted = ConcurrentHashMap.newKeySet();

    public QualityRepairService(HeronVideoVersionMapper versionMapper, HeronNewsWorkMapper workMapper,
                                IProductionService production, NewsProductionDomain domain,
                                NewsScriptAuthor author, KnowledgeScriptAuthor knowledgeAuthor,
                                DatasetScriptAuthor datasetAuthor, Environment environment,
                                @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler) {
        this.versionMapper = versionMapper;
        this.workMapper = workMapper;
        this.production = production;
        this.domain = domain;
        this.author = author;
        this.knowledgeAuthor = knowledgeAuthor;
        this.datasetAuthor = datasetAuthor;
        this.environment = environment;
        this.scheduler = scheduler;
    }

    /**
     * 启动扫描：{@code heron.news.quality-repair-enabled} 为真时每 120 秒跑一轮 {@link #sweep()}
     * （先复检已完成的修复版本，再挑一条可修报告发起修复）；开关关闭时只记日志，不注册定时任务，
     * 修复只能由显式调用 {@link #attempt(long)} 触发。
     */
    @PostConstruct
    public void start() {
        if (!enabled()) {
            log.info("审稿修复扫描未启用（{} 未打开）；修复链路只能由显式调用触发", ENABLED_KEY);
            return;
        }
        scheduler.scheduleWithFixedDelay(this::sweep, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log.info("审稿修复扫描已启用：每 {} 秒检查一次可修报告", POLL_SECONDS);
    }

    /** 一轮扫描：先复检已完成的修复版本，再挑一条可修的报告。 */
    void sweep() {
        try {
            recheckCompleted();
        } catch (RuntimeException e) {
            log.warn("审稿修复复检扫描异常", e);
        }
        try {
            attemptNext();
        } catch (RuntimeException e) {
            log.warn("审稿修复发起扫描异常", e);
        }
    }

    /**
     * 一次修复尝试的结果。
     *
     * <p>status：missing / not_complete / attempted_before / inflight / locked / superseded /
     * none / model_failed / out_of_scope / no_change / contract_failed / submit_rejected / submitted。
     */
    public record Attempt(String status, long previousVersionId, Long newVersionId,
                          List<String> changedFields, String detail) {
    }

    /** 一次复检的结论：repaired / rolled_back / not_applied。 */
    public record Recheck(String status, long versionId, long previousVersionId, boolean applied,
                          boolean passed, List<String> failures) {
    }

    /**
     * 对一个已完成版本发起一次定向修复。
     *
     * <p>只修白名单判据，最多一次：修不好说明问题不在「某一句怎么写」，再试几轮只是多烧一次渲染。
     * 修复稿必须与原稿逐字段对齐（白名单之外的改动在 {@link QualityRepairPlanner#narrow} 里被丢掉），
     * 再过一次契约校验，才允许提交。
     */
    public Attempt attempt(long versionId) {
        HeronVideoVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            return new Attempt("missing", versionId, null, List.of(), "版本不存在");
        }
        if (version.getScript() == null || version.getQualityReport() == null
            || version.getArtifactManifest() == null || version.getTaskId() == null) {
            return new Attempt("not_complete", versionId, null, List.of(), "版本尚未出片，没有可修的报告");
        }
        JsonNode snapshot = JSON.readTree(version.getInputSnapshot());
        if (snapshot.path("editorial").has(REPAIR_KEY)) {
            return new Attempt("attempted_before", versionId, null, List.of(), "这一版本身就是一次修复，不叠加");
        }
        JsonNode report = JSON.readTree(version.getQualityReport());
        if (report.path("repair").path("attempted").asInt(0) >= QualityRepairPlanner.MAX_ATTEMPTS) {
            return new Attempt("attempted_before", versionId, null, List.of(), "修复次数已达上限");
        }
        var plan = QualityRepairPlanner.planForReproduction(report);
        if (plan.isEmpty()) {
            return new Attempt("none", versionId, null, List.of(), "报告里没有可修判据");
        }
        QualityRepairPlanner.RepairPlan repairPlan = plan.get();
        HeronNewsWork work = workMapper.selectById(version.getWorkId());
        if (work == null) {
            return new Attempt("missing", versionId, null, List.of(), "作品不存在");
        }
        if (work.getActiveTaskId() != null) {
            return new Attempt("inflight", versionId, null, List.of(), "该作品已有在途任务");
        }
        if (version.getPublishAt() != null) {
            return new Attempt("locked", versionId, null, List.of(), "该版本已发布，不再修");
        }
        // 已经出过更新的版本：要么是上次修复，要么是人重出的，两种都不该再自动补一版。
        Long newer = versionMapper.selectCount(QueryBuilder.lambda(HeronVideoVersion.class)
            .eq(HeronVideoVersion::getWorkId, version.getWorkId())
            .gt(HeronVideoVersion::getVersionNo, version.getVersionNo()).build());
        if (newer != null && newer > 0) {
            return new Attempt("superseded", versionId, null, List.of(), "已有更新的版本，不再修这一版");
        }

        String repaired;
        try {
            repaired = repairScript(version, snapshot, repairPlan);
        } catch (RuntimeException e) {
            return new Attempt("model_failed", versionId, null, List.of(), "改稿失败：" + e.getMessage());
        }
        QualityRepairPlanner.Narrowed narrowed =
            QualityRepairPlanner.narrow(version.getScript(), repaired, repairPlan);
        if (!narrowed.violations().isEmpty()) {
            return new Attempt("out_of_scope", versionId, null, List.of(),
                "修复稿越界：" + String.join("；", narrowed.violations()));
        }
        if (!narrowed.changed()) {
            return new Attempt("no_change", versionId, null, List.of(), "模型没有改动白名单字段");
        }
        String frozen = narrowed.script();
        List<String> contract = contractViolations(version, frozen);
        if (!contract.isEmpty()) {
            return new Attempt("contract_failed", versionId, null, narrowed.changedFields(),
                "修复稿没过契约：" + String.join("；", contract));
        }

        Map<String, Object> selection = JsonUtils.parseMap(JSON.writeValueAsString(snapshot.path("selection")));
        Map<String, Object> editorial = JsonUtils.parseMap(JSON.writeValueAsString(snapshot.path("editorial")));
        Map<String, Object> directive = new LinkedHashMap<>();
        directive.put("attempt", 1);
        directive.put("previousVersionId", version.getId());
        directive.put("criteria", QualityRepairPlanner.criterionIds(repairPlan));
        directive.put("changedFields", narrowed.changedFields());
        directive.put("scriptSha256", FrozenContext.hash(frozen));
        directive.put("script", JSON.readTree(frozen));
        editorial.put(REPAIR_KEY, directive);
        ProductionReceiptVo receipt;
        try {
            receipt = production.submit(new ProductionSubmitBo(
                ProductionBizType.fromKind(snapshot.path("kind").asString("")), work.getId(),
                "repair:" + version.getId() + ":" + version.getVersionNo(), work.getRevision(),
                null, null, new EditorialOverride(selection, editorial)));
        } catch (RuntimeException e) {
            return new Attempt("submit_rejected", versionId, null, narrowed.changedFields(),
                "提交重生产被拒：" + e.getMessage());
        }
        Long newVersionId = receipt == null ? null : receipt.versionId();
        log.info("审稿修复已提交重生产：版本 {} → 版本 {}", versionId, newVersionId);
        return new Attempt("submitted", versionId, newVersionId, narrowed.changedFields(),
            "修复稿已冻结，等待重跑与复检");
    }

    /**
     * 复检一次已完成的修复版本。
     *
     * <p>先比稿子指纹：新版本渲染的稿子必须就是冻结的那份修复稿，否则这次重跑没有按修复稿走，
     * 结论只能记「没复检」。
     */
    public Recheck recheck(long versionId) {
        HeronVideoVersion version = versionMapper.selectById(versionId);
        if (version == null || version.getQualityReport() == null || version.getScript() == null
            || version.getArtifactManifest() == null) {
            return null;
        }
        JsonNode snapshot = JSON.readTree(version.getInputSnapshot());
        JsonNode directive = snapshot.path("editorial").path(REPAIR_KEY);
        if (!directive.isObject() || !directive.path("previousVersionId").isNumber()) {
            return null;
        }
        JsonNode report = JSON.readTree(version.getQualityReport());
        if (report.path("repair").path("rechecked").asBoolean(false)) {
            return null;
        }
        long previous = directive.path("previousVersionId").asLong();
        List<String> criteria = strings(directive.path("criteria"));
        List<String> changed = strings(directive.path("changedFields"));
        boolean applied = applied(directive, version, changed);
        if (!applied) {
            writeReport(version, report, changed, false, false, previous, criteria);
            return new Recheck("not_applied", versionId, previous, false, false,
                List.of("重跑实际渲染的稿子不是冻结的修复稿"));
        }
        List<String> failures = failures(report, criteria);
        if (failures.isEmpty()) {
            writeReport(version, report, changed, true, true, previous, criteria);
            appendRepairGate(version, previous, criteria, changed);
            log.info("审稿修复过闸：版本 {} 点名判据全部通过", versionId);
            return new Recheck("repaired", versionId, previous, true, true, List.of());
        }
        rollback(version, previous, report, changed, criteria);
        return new Recheck("rolled_back", versionId, previous, true, false, failures);
    }

    /**
     * 修复稿到底有没有进这条片子。
     *
     * <p>先比整稿指纹：一致就说明渲染的就是冻结的那份修复稿。
     * 指纹不一致时再看点名改动的字段：写稿链路的审稿环节可能又润色了别的段落，
     * 只要修复的那几段文字原样在，就算这次修复生效了——不能因为别处被合法改动就否认修复。
     */
    private static boolean applied(JsonNode directive, HeronVideoVersion version, List<String> changed) {
        if (FrozenContext.hash(version.getScript()).equals(directive.path("scriptSha256").asString(""))) {
            return true;
        }
        JsonNode frozen = directive.path("script");
        if (!frozen.isObject()) {
            return false;
        }
        try {
            JsonNode rendered = JSON.readTree(version.getScript());
            for (String field : changed) {
                if (!Objects.equals(valueAt(frozen, field), valueAt(rendered, field))) {
                    return false;
                }
            }
            return !changed.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 取稿子里某个字段的文字：只认 closing 与 scenes[i].<field> 这两种路径。 */
    private static String valueAt(JsonNode script, String field) {
        if ("closing".equals(field)) {
            return script.path("closing").asString("");
        }
        int open = field.indexOf('[');
        int close = field.indexOf(']');
        if (open < 0 || close < open) {
            return "";
        }
        int index;
        try {
            index = Integer.parseInt(field.substring(open + 1, close));
        } catch (NumberFormatException e) {
            return "";
        }
        String name = field.substring(Math.min(close + 2, field.length()));
        return script.path("scenes").path(index).path(name).asString("");
    }

    /** 定向改稿：各线走各自的修复通道，事实类输入从上一版冻结产物里读，保证只变文字。 */
    private String repairScript(HeronVideoVersion version, JsonNode snapshot,
                                QualityRepairPlanner.RepairPlan plan) {
        ProductionRunVo run = new ProductionRunVo(version.getTaskId(),
            ProductionBizType.fromKind(snapshot.path("kind").asString("")),
            version.getWorkId(), version.getId(), 1, "", version.getInputSnapshot());
        List<String> notes = QualityRepairPlanner.notes(plan);
        String script = version.getScript();
        if (domain.dataBoard(version)) {
            EditorialStory story = EditorialStory.fromJson(snapshot.path("selection").path("story"));
            return datasetAuthor.repair(run, artifact(version, "dataset"), script, notes, story).script();
        }
        if (domain.trend(version)) {
            return datasetAuthor.repairTrend(run, artifact(version, "dataset"),
                artifact(version, "trend"), script, notes).script();
        }
        if (domain.knowledgeExplainer(version)) {
            return knowledgeAuthor.repair(run, script, notes);
        }
        return author.repair(run, script, notes);
    }

    /** 修复稿的契约复核：与生产同一条口径，只是数据来自冻结产物。 */
    private List<String> contractViolations(HeronVideoVersion version, String script) {
        try {
            JsonNode node = JSON.readTree(script);
            if (domain.dataBoard(version)) {
                return DatasetScriptContract.violations(node, JSON.readTree(artifact(version, "dataset")));
            }
            if (domain.trend(version)) {
                return TrendContract.violations(JSON.readTree(artifact(version, "trend")), node,
                    JSON.readTree(artifact(version, "dataset")));
            }
            return NewsScriptContract.violations(node);
        } catch (RuntimeException e) {
            return List.of("修复稿或冻结产物不可读：" + e.getMessage());
        }
    }

    /** 从冻结产物清单里读一份 JSON 文本；产物缺失就是缺，不重新取数。 */
    private String artifact(HeronVideoVersion version, String kind) {
        List<ProductionArtifactVo> files = JsonUtils.parseArray(version.getArtifactManifest(),
            ProductionArtifactVo.class);
        for (ProductionArtifactVo file : files) {
            if (!kind.equals(file.kind())) {
                continue;
            }
            try (NewsFileVo opened = domain.openFile(version, file)) {
                return new String(opened.content().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("产物不可读：" + kind, e);
            }
        }
        throw new IllegalStateException("缺少产物：" + kind);
    }

    /** 点名的判据必须全过；缺判据按未通过处理，不当成通过。 */
    public static List<String> failures(JsonNode report, List<String> criteria) {
        List<String> failures = new ArrayList<>();
        for (String id : criteria) {
            JsonNode item = null;
            for (JsonNode candidate : report.path("criteria")) {
                if (id.equals(candidate.path("id").asString(""))) {
                    item = candidate;
                    break;
                }
            }
            if (item == null) {
                failures.add(id + "：复检报告里没有这条判据");
            } else if (!"pass".equals(item.path("verdict").asString(""))) {
                failures.add(id + "：" + item.path("verdict").asString(""));
            }
        }
        return List.copyOf(failures);
    }

    /** 把复检结论写进质量报告：只动 repair 块与 notes，报告的判据与依据原样保留。 */
    private void writeReport(HeronVideoVersion version, JsonNode report, List<String> changed,
                             boolean rechecked, boolean passed, long previous, List<String> criteria) {
        ObjectNode root = report.isObject() ? ((ObjectNode) report).deepCopy() : JsonNodeFactory.instance.objectNode();
        fillRepair(root, changed, rechecked, passed, previous, criteria);
        if (!rechecked) {
            appendNote(root, NOTE_NOT_RECHECKED);
        }
        saveReport(version, root);
    }

    /** 回滚：让上一版重新成为可发布版本，新版本留痕为不通过。 */
    private void rollback(HeronVideoVersion version, long previous, JsonNode report, List<String> changed,
                          List<String> criteria) {
        ObjectNode root = ((ObjectNode) report).deepCopy();
        fillRepair(root, changed, true, false, previous, criteria);
        appendNote(root, NOTE_ROLLED_BACK);
        saveReport(version, root);
        int moved = workMapper.lambda().set(HeronNewsWork::getCurrentVersionId, previous)
            .eq(HeronNewsWork::getId, version.getWorkId())
            .eq(HeronNewsWork::getCurrentVersionId, version.getId()).updateCount();
        if (moved != 1) {
            log.warn("审稿修复回滚指针未移动：版本 {} 已不是当前版本", version.getId());
        }
        versionMapper.lambda().set(HeronVideoVersion::getReviewStatus, "rejected")
            .set(HeronVideoVersion::getReviewReason, "定向修复后仍未过 quality 闸，已回滚到上一版")
            .eq(HeronVideoVersion::getId, version.getId()).updateCount();
        log.info("审稿修复回滚：版本 {} 未过闸，已指回版本 {}", version.getId(), previous);
    }

    private static void fillRepair(ObjectNode root, List<String> changed, boolean rechecked, boolean passed,
                                   long previous, List<String> criteria) {
        JsonNode existing = root.path("repair");
        ObjectNode repair = existing.isObject() ? (ObjectNode) existing : root.putObject("repair");
        repair.put("attempted", 1);
        repair.put("max", QualityRepairPlanner.MAX_ATTEMPTS);
        repair.set("changedSegments", JSON.valueToTree(changed));
        repair.put("rechecked", rechecked);
        repair.put("passed", passed);
        repair.put("previousVersionId", previous);
        repair.set("criteria", JSON.valueToTree(criteria));
    }

    private void saveReport(HeronVideoVersion version, ObjectNode root) {
        versionMapper.lambda().set(HeronVideoVersion::getQualityReport, root.toString())
            .eq(HeronVideoVersion::getId, version.getId()).updateCount();
    }

    /** 报告里的 notes 只认约定的几个词：没复检、已回滚都要留痕，不能悄悄改结论。 */
    private static void appendNote(ObjectNode report, String note) {
        Set<String> notes = new LinkedHashSet<>(strings(report.path("notes")));
        notes.add(note);
        report.set("notes", JSON.valueToTree(List.copyOf(notes)));
    }

    /**
     * 过闸的修复补一条 repair 闸。
     *
     * <p>checks 只放通过的闸（失败闸会让整份检查报告不成立），所以只有修复成功后写这一条；
     * 回滚的结论留在质量报告的 repair 块与 notes 里。
     */
    private void appendRepairGate(HeronVideoVersion version, long previous, List<String> criteria,
                                  List<String> changed) {
        try {
            JsonNode checks = JSON.readTree(version.getChecks());
            if (checks == null || !checks.isArray()) {
                return;
            }
            List<JsonNode> list = new ArrayList<>();
            checks.forEach(list::add);
            Map<String, Object> measured = new LinkedHashMap<>();
            measured.put("attempted", 1);
            measured.put("previousVersionId", previous);
            measured.put("criteria", criteria);
            measured.put("changedSegments", changed);
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("gate", GATE_REPAIR);
            gate.put("inputLocator", "version:" + previous);
            gate.put("passed", true);
            gate.put("reason", "定向修复后点名判据全部通过");
            gate.put("measured", measured);
            list.add(JSON.valueToTree(gate));
            versionMapper.lambda().set(HeronVideoVersion::getChecks, JSON.writeValueAsString(list))
                .eq(HeronVideoVersion::getId, version.getId()).updateCount();
        } catch (RuntimeException e) {
            // 闸写不进去不影响「修好了」这个结论：报告里已经记了 rechecked=true。
            log.warn("审稿修复闸写不进检查报告：版本 {}", version.getId(), e);
        }
    }

    /** 扫描：复检所有已完成但还没复检的修复版本。 */
    void recheckCompleted() {
        List<HeronVideoVersion> rows = versionMapper.selectList(QueryBuilder.lambda(HeronVideoVersion.class)
            .isNotNull(HeronVideoVersion::getQualityReport)
            .isNotNull(HeronVideoVersion::getArtifactManifest)
            .like(HeronVideoVersion::getInputSnapshot, REPAIR_KEY)
            .orderByDesc(HeronVideoVersion::getId).last("LIMIT " + SCAN_LIMIT).build());
        for (HeronVideoVersion row : rows) {
            try {
                Recheck outcome = recheck(row.getId());
                if (outcome != null) {
                    log.info("审稿修复复检：版本 {} → {}", row.getId(), outcome.status());
                }
            } catch (RuntimeException e) {
                log.warn("审稿修复复检失败：版本 {}", row.getId(), e);
            }
        }
    }

    /** 扫描：挑一条可修的报告发起修复，一轮最多一条，避免同时烧多条流水线。 */
    void attemptNext() {
        List<HeronVideoVersion> rows = versionMapper.selectList(QueryBuilder.lambda(HeronVideoVersion.class)
            .isNotNull(HeronVideoVersion::getQualityReport)
            .isNotNull(HeronVideoVersion::getArtifactManifest)
            .eq(HeronVideoVersion::getReviewStatus, "pending")
            .isNull(HeronVideoVersion::getPublishAt)
            .orderByDesc(HeronVideoVersion::getId).last("LIMIT " + SCAN_LIMIT).build());
        for (HeronVideoVersion row : rows) {
            if (!attempted.add(row.getId())) {
                continue;
            }
            Attempt outcome = attempt(row.getId());
            if ("submitted".equals(outcome.status())) {
                log.info("审稿修复：版本 {} 已提交重生产，改动 {}", row.getId(), outcome.changedFields());
                return;
            }
            if ("none".equals(outcome.status())) {
                attempted.remove(row.getId());
                continue;
            }
            log.info("审稿修复跳过版本 {}：{}（{}）", row.getId(), outcome.status(), outcome.detail());
        }
    }

    /** 部署排障用：这个开关决定扫描是否运行。 */
    public boolean enabled() {
        return environment.getProperty(ENABLED_KEY, Boolean.class, false);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(item.asString(""));
        }
        return List.copyOf(out);
    }
}
