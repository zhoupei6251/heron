package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.EditorialStory;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 线内编辑：总编辑定了走哪条线，这里定具体素材。
 *
 * <p>关键约束是「认领之前完成」：所有选择在这一步定完，生产只读冻结快照。
 * 之前自动线是先预选候选、总编辑再定线、最后按模板名前缀 findFirst 取第一个模板——
 * 三步各选一次，结果互相不认识。
 *
 * <p>数据看板线还有一条更硬的约束：<b>没故事就不做</b>。这条线的片子必须先有一份
 * 可校验的策划结论（story/1），写标题与写旁白都按它来；策划不通过就换下一条候选，
 * 全不通过则这条线本轮不出片。趋势线不走策划：它的结论由 trend/1 的机器判定驱动。
 */
@Service
@RequiredArgsConstructor
public class EditorialLineEditor {

    /** 线内编辑不在生产任务里，用量用 0 任务号走纯决策调用。 */
    private static final long NO_TASK = 0L;
    /**
     * 看板线最多试几条候选机会：一条不行换下一条，但必须有上限。
     * 每条都要一次取数 + 一次模型调用，不封顶就会把一个槽位的时长吃光。
     */
    private static final int MAX_STORY_CANDIDATES = 3;
    /** 本地 mapper：证据解析与上下文构造要能在纯单测里跑，不依赖 Spring 上下文。 */
    private static final tools.jackson.databind.json.JsonMapper JSON =
        tools.jackson.databind.json.JsonMapper.builder().build();

    private final NewsCandidateScreener screener;
    private final DatasetRouter datasetRouter;
    private final DatasetStoryPlanner storyPlanner;

    /**
     * 总编辑定线之后的唯一入口。候选与数据源都只从传入的池子里取，不再自己查库——
     * 池子的边界（来源白名单、24 小时时效、7 天数据源去重）在建池时已经生效，
     * 重查一次等于把那些边界丢在门外。
     *
     * <p>选不到就回退另一条线再试一次：日更账号直接弃更的代价比多调一次模型高得多。
     * 回退必须留痕，否则槽位上的「选了数据看板」和片子实际走的解说线永远对不上。
     *
     * @param focusCandidateId   总编辑锁定的候选，可为空；解说线、知识线生效
     * @param focusOpportunityId 总编辑锁定的数据机会，可为空；仅数据看板线生效
     */
    public Optional<EditorialChoice> select(String line, EditorialCandidatePool pool, String intent,
                                            Long focusCandidateId) {
        return select(line, pool, intent, focusCandidateId, null);
    }

    public Optional<EditorialChoice> select(String line, EditorialCandidatePool pool, String intent,
                                            Long focusCandidateId, Long focusOpportunityId) {
        // 策划拒绝是候选级的：一条不行换下一条，这些原因码是「今天为什么没选它」的唯一证据
        List<String> rejects = new ArrayList<>();
        if (EditorialDecisionService.KNOWLEDGE_EXPLAINER.equals(line)) {
            Optional<EditorialSelection> knowledge = editKnowledge(pool.knowledge(), intent, focusCandidateId);
            if (knowledge.isPresent()) {
                return Optional.of(new EditorialChoice(knowledge.get(), line, false, null, rejects));
            }
            // 备用线也会空：池子里的知识候选都在重试中被剔掉时，按既定顺序回退一次
            Optional<EditorialSelection> second = editExplainer(pool.articles(), intent, focusCandidateId);
            if (second.isPresent()) {
                return Optional.of(new EditorialChoice(second.get(), line, true, "knowledge_empty", rejects));
            }
            DatasetPlan plan = planDatasetLine(EditorialDecisionService.DATA_BOARD,
                pool.datasets(), pool.opportunities(), focusOpportunityId, "");
            rejects.addAll(plan.rejects());
            return plan.selection()
                .map(selection -> new EditorialChoice(selection, line, true, "knowledge_empty", rejects));
        }
        boolean trend = EditorialDecisionService.TREND_INSIGHT.equals(line);
        boolean datasetLine = trend || EditorialDecisionService.DATA_BOARD.equals(line);
        Optional<EditorialSelection> first;
        if (datasetLine) {
            DatasetPlan plan = planDatasetLine(trend ? EditorialDecisionService.TREND_INSIGHT
                    : EditorialDecisionService.DATA_BOARD,
                pool.datasets(), pool.opportunities(), focusOpportunityId, intent);
            rejects.addAll(plan.rejects());
            first = plan.selection();
        } else {
            first = editExplainer(pool.articles(), intent, focusCandidateId);
        }
        if (first.isPresent()) {
            return Optional.of(new EditorialChoice(first.get(), line, false, null, rejects));
        }
        String reason = trend ? "trend_empty" : datasetLine ? "data_board_empty" : "explainer_empty";
        Optional<EditorialSelection> second;
        if (datasetLine) {
            // 跨线回退不带旧方向的角度：geo 意图塞进新闻片，写稿只会产出拧巴的片子
            second = editExplainer(pool.articles(), "", focusCandidateId);
        } else {
            DatasetPlan plan = planDatasetLine(EditorialDecisionService.DATA_BOARD,
                pool.datasets(), pool.opportunities(), focusOpportunityId, intent);
            rejects.addAll(plan.rejects());
            second = plan.selection();
        }
        if (second.isPresent()) {
            return Optional.of(new EditorialChoice(second.get(), line, true, reason, rejects));
        }
        // 知识线是最后一条：其余线当天都没素材时，它也只剩这一条路
        return editKnowledge(pool.knowledge(), intent, focusCandidateId)
            .map(selection -> new EditorialChoice(selection, line, true, reason + "_knowledge", rejects));
    }

    /**
     * 知识解释线：在知识包主题候选里选一条，再选版式。
     * 候选的 evidence.items 带选定的 entryId 列表，直接复用为 selection 的引用集合。
     */
    public Optional<EditorialSelection> editKnowledge(List<HeronNewsCandidate> pool, String intent,
                                                      Long focusCandidateId) {
        List<HeronNewsCandidate> usable = pool.stream()
            .filter(candidate -> !evidenceIds(candidate).isEmpty()).toList();
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        HeronNewsCandidate candidate = focusCandidateId == null ? null : usable.stream()
            .filter(item -> focusCandidateId.equals(item.getId())).findFirst().orElse(null);
        if (candidate == null) {
            Long candidateId = screener.screen(usable).orElse(usable.get(0).getId());
            Long picked = candidateId;
            candidate = usable.stream()
                .filter(item -> picked.equals(item.getId())).findFirst().orElse(usable.get(0));
        }
        HeronNewsCandidate chosen = candidate;
        // 导演架构：视觉决策后移到 VisualTreatment/Storyboard，线内编辑只留风格 id 过渡值
        return Optional.of(new EditorialSelection(EditorialDecisionService.KNOWLEDGE_EXPLAINER,
            chosen.getId(), chosen.getSourceKey(), false, EditorialSelection.LineStyle.KNOWLEDGE, intent, evidenceIds(chosen)));
    }

    /**
     * 解说线：在「有正文」的候选里选一条，再选版式。
     * 总编辑锁定了候选且那条候选确实有正文时，直接用它；否则交给筛稿器。
     * 空结果表示这条线今天没有能做的素材，由调用方回退到另一条线。
     */
    public Optional<EditorialSelection> editExplainer(List<HeronNewsCandidate> pool, String intent,
                                                      Long focusCandidateId) {
        List<HeronNewsCandidate> withBody = pool.stream()
            .filter(candidate -> !evidenceIds(candidate).isEmpty()).toList();
        if (withBody.isEmpty()) {
            return Optional.empty();
        }
        HeronNewsCandidate candidate = focusCandidateId == null ? null : withBody.stream()
            .filter(item -> focusCandidateId.equals(item.getId())).findFirst().orElse(null);
        if (candidate == null) {
            Long candidateId = screener.screen(withBody).orElse(withBody.get(0).getId());
            Long picked = candidateId;
            candidate = withBody.stream()
                .filter(item -> picked.equals(item.getId())).findFirst().orElse(withBody.get(0));
        }
        HeronNewsCandidate chosen = candidate;
        // 导演架构：视觉决策后移到 VisualTreatment/Storyboard，线内编辑只留风格 id 过渡值
        return Optional.of(new EditorialSelection(EditorialDecisionService.NEWS_EXPLAINER,
            chosen.getId(), null, false, EditorialSelection.LineStyle.EXPLAINER, intent, evidenceIds(chosen)));
    }

    /**
     * 数据看板线：从**建池时冻结的**数据源候选里挑一条 + 选版式 + 定故事。
     * 这里不再查库：启用状态、探测结果、7 天去重都已在建池时判定过，
     * 重查一次就会出现「池子里说能用、选的时候已经不能用」的两套事实。
     * 没有可用数据源是正常结果，交给调用方回退到解说线。
     *
     * <p>总编辑锁定了数据机会时直接用它绑定的源：那条机会本身就是「今天为什么做这个」，
     * 再让选源器重挑一次等于把决策推翻。
     */
    public Optional<EditorialSelection> editDataBoard(List<HeronDataSource> datasets,
                                                      List<HeronDataOpportunity> opportunities,
                                                      Long focusOpportunityId) {
        return editDatasetLine(EditorialDecisionService.DATA_BOARD, datasets, opportunities, focusOpportunityId);
    }

    /**
     * 数据线（看板 / 趋势）共用一次选源：机会优先，其次按选源器。
     *
     * <p>趋势线只认能算出判定的机会：形态不是 wide、点数不够的时间序列，
     * 机器只能给「样本不足」，那不是趋势洞察，不该占一个趋势槽位。
     */
    public Optional<EditorialSelection> editDatasetLine(String line, List<HeronDataSource> datasets,
                                                        List<HeronDataOpportunity> opportunities,
                                                        Long focusOpportunityId) {
        return planDatasetLine(line, datasets, opportunities, focusOpportunityId, "").selection();
    }

    /**
     * 数据线的一次策划结果：选中了什么，以及途中被策划闸否掉的候选。
     *
     * @param selection 选中的素材；空表示这条线本轮没有能做的素材
     * @param rejects   被否掉的候选，形如 {@code 源键:story_duplicate}
     */
    private record DatasetPlan(Optional<EditorialSelection> selection, List<String> rejects) {

        static DatasetPlan empty(List<String> rejects) {
            return new DatasetPlan(Optional.empty(), rejects);
        }
    }

    /**
     * 数据线的一次完整策划。
     *
     * <p>看板线在这层做「没故事就不做」：按分数依次试前几条机会，每条都要先出一份
     * 可校验的策划结论才认；机会都不成立再退到选源器兜底，兜底同样要有故事。
     * 趋势线不走这套：它的结论由 trend/1 的机器判定驱动，写稿时带着判定即可。
     */
    private DatasetPlan planDatasetLine(String line, List<HeronDataSource> datasets,
                                        List<HeronDataOpportunity> opportunities,
                                        Long focusOpportunityId, String intent) {
        boolean trend = EditorialDecisionService.TREND_INSIGHT.equals(line);
        List<String> rejects = new ArrayList<>();
        List<HeronDataOpportunity> usable = opportunities == null ? List.of()
            : trend ? opportunities.stream().filter(item -> TrendAnalysis.routable(opportunityOf(item))).toList()
            : opportunities;
        HeronDataOpportunity focused = focusOpportunityId == null ? null
            : usable.stream()
                .filter(item -> focusOpportunityId.equals(item.getId()))
                .findFirst().orElse(null);
        if (focused == null && !usable.isEmpty()) {
            // 没锁定就用分数最高的那条：选源器拿到的是写死的题材，不可能比算出来的机会更懂今天
            focused = usable.get(0);
        }
        if (focused != null && trend) {
            return new DatasetPlan(trendSelection(line, datasets, focused), rejects);
        }
        if (focused != null) {
            // 看板线：锁定时只试锁定的那条（不擅自换题），没锁定时按分数取前几条依次试
            List<HeronDataOpportunity> candidates = focusOpportunityId == null
                ? usable.stream().limit(MAX_STORY_CANDIDATES).toList()
                : List.of(focused);
            for (HeronDataOpportunity candidate : candidates) {
                Optional<EditorialSelection> selection = planOpportunity(line, datasets, candidate, rejects);
                if (selection.isPresent()) {
                    return new DatasetPlan(selection, rejects);
                }
            }
            // 机会都不成立：退到选源器兜底，兜底也要有故事
            return planDigest(line, datasets, rejects, intent);
        }
        if (trend) {
            return new DatasetPlan(digestSelection(line, datasets), rejects);
        }
        return planDigest(line, datasets, rejects, intent);
    }

    /**
     * 趋势线选中机会：角度由机会自己的信号决定，结论交给 trend/1 的机器判定。
     */
    private Optional<EditorialSelection> trendSelection(String line, List<HeronDataSource> datasets,
                                                        HeronDataOpportunity chosen) {
        DatasetStoryAngle.Angle angle = DatasetStoryAngle.pick(chosen.getShape(),
            signalsOf(chosen.getSignals()), chosen.getPeriodStart(), chosen.getPeriodEnd());
        // digest=false：这是被机会明确选中的源，不是「没题材时的速览」
        return Optional.of(new EditorialSelection(line,
            null, chosen.getSourceKey(), false, EditorialSelection.LineStyle.TREND, text(angle, true), List.of(),
            chosen.getId(), opportunityOf(chosen)));
    }

    /**
     * 看板线选中机会：先出一份策划结论，通过了才认这条机会。
     */
    private Optional<EditorialSelection> planOpportunity(String line, List<HeronDataSource> datasets,
                                                        HeronDataOpportunity candidate,
                                                        List<String> rejects) {
        HeronDataSource source = sourceOf(datasets, candidate.getSourceKey());
        if (source == null) {
            rejects.add(candidate.getSourceKey() + ":story_source_missing");
            return Optional.empty();
        }
        int points = candidate.getWindowPoints() == null || candidate.getWindowPoints() <= 0
            ? DatasetStoryPlanner.DEFAULT_POINTS : candidate.getWindowPoints();
        DatasetStoryPlanner.Result result = storyPlanner.plan(decisionRun(), source, points,
            "opportunity", candidate.getId(), candidate.getHeadline(), candidate.getSignals());
        if (!result.accepted()) {
            rejects.add(candidate.getSourceKey() + ":" + result.rejectCode());
            return Optional.empty();
        }
        // 导演架构：视觉决策后移到 VisualTreatment/Storyboard（Profile 锚定），线内编辑不再选模板。
        // templateId 过渡为风格 id；geo 故事保留 3D 声明——2D 画不出经纬度，这个事实与渲染方式无关。
        String styleId = VisualMode.of(result.story()) == VisualMode.THREE_D
            ? EditorialSelection.LineStyle.DATA_BOARD_3D : EditorialSelection.LineStyle.DATA_BOARD;
        return Optional.of(new EditorialSelection(line,
            null, candidate.getSourceKey(), false, styleId, storyAngle(result.story(), candidate),
            List.of(), candidate.getId(), opportunityOf(candidate), result.story()));
    }

    /**
     * 看板线走选源器兜底：速览也得有故事，否则这条线本轮就不出片。
     */
    private DatasetPlan planDigest(String line, List<HeronDataSource> datasets, List<String> rejects,
                                   String intent) {
        if (datasets == null || datasets.isEmpty()) {
            return DatasetPlan.empty(rejects);
        }
        DatasetRouter.Route route;
        try {
            route = datasetRouter.pick(decisionRun(), decisionContext(intent), datasets);
        } catch (RuntimeException e) {
            return DatasetPlan.empty(rejects);
        }
        if (route == null) {
            return DatasetPlan.empty(rejects);
        }
        HeronDataSource source = sourceOf(datasets, route.sourceKey());
        if (source == null) {
            rejects.add(route.sourceKey() + ":story_source_missing");
            return DatasetPlan.empty(rejects);
        }
        DatasetStoryPlanner.Result result = storyPlanner.plan(decisionRun(), source,
            DatasetStoryPlanner.DEFAULT_POINTS, "digest", null, null, null);
        if (!result.accepted()) {
            rejects.add(route.sourceKey() + ":" + result.rejectCode());
            return DatasetPlan.empty(rejects);
        }
        EditorialStory story = result.story();
        // 导演架构：速览同样不选模板，风格 id 过渡值由视觉模式决定（geo → 3D）
        String styleId = VisualMode.of(story) == VisualMode.THREE_D
            ? EditorialSelection.LineStyle.DATA_BOARD_3D : EditorialSelection.LineStyle.DATA_BOARD;
        return new DatasetPlan(Optional.of(new EditorialSelection(line,
            null, route.sourceKey(), route.digest(), styleId, storyAngle(story), List.of(),
            null, null, story).withSourceReason(route.reason())), rejects);
    }

    /** 趋势线的兜底：没有能算出判定的机会时走选源器，稿子不带策划结论。 */
    private Optional<EditorialSelection> digestSelection(String line, List<HeronDataSource> datasets) {
        if (datasets == null || datasets.isEmpty()) {
            return Optional.empty();
        }
        DatasetRouter.Route route;
        try {
            route = datasetRouter.pick(decisionRun(), decisionContext(), datasets);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (route == null) {
            return Optional.empty();
        }
        return Optional.of(new EditorialSelection(line,
            null, route.sourceKey(), route.digest(), EditorialSelection.LineStyle.TREND, text(DatasetStoryAngle.DIGEST_ANGLE, true),
            List.of()).withSourceReason(route.reason()));
    }

    /** 池子里的数据源：机会与兜底都要按 sourceKey 找到它才能拿到类别与许可口径。 */
    private static HeronDataSource sourceOf(List<HeronDataSource> datasets, String sourceKey) {
        if (datasets == null || sourceKey == null) {
            return null;
        }
        return datasets.stream()
            .filter(item -> sourceKey.equals(item.getSourceKey()))
            .findFirst().orElse(null);
    }

    /** 故事角度优先用策划的一句话：那就是「这条片子讲的是什么事」。 */
    private static String storyAngle(EditorialStory story, HeronDataOpportunity candidate) {
        if (story != null && story.oneLine() != null && !story.oneLine().isBlank()) {
            return story.oneLine();
        }
        DatasetStoryAngle.Angle angle = DatasetStoryAngle.pick(candidate.getShape(),
            signalsOf(candidate.getSignals()), candidate.getPeriodStart(), candidate.getPeriodEnd());
        return angle == null ? "" : angle.text();
    }

    private static String storyAngle(EditorialStory story) {
        if (story != null && story.oneLine() != null && !story.oneLine().isBlank()) {
            return story.oneLine();
        }
        return "";
    }

    /** 趋势线的故事角度要落到「变化意味着什么」，否则写稿只会写成第二份数据看板。 */
    private static String text(DatasetStoryAngle.Angle angle, boolean trend) {
        return text(angle == null ? "" : angle.text(), trend);
    }

    private static String text(String angle, boolean trend) {
        String base = angle == null ? "" : angle;
        if (!trend) {
            return base;
        }
        String suffix = "；趋势线：讲变化意味着什么，方向以 trend/1 的机器判定为准";
        return base + suffix;
    }

    /** 机会的信号 JSON：解析不了就当没有信号，角度退回兜底。 */
    private static JsonNode signalsOf(String signals) {
        if (signals == null || signals.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(signals);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 机会的取数口径与头线：认领时一次定完，冻结后生产只读这一份。
     *
     * <p>不在冻结时回查机会表：机会表会被下一次分析更新，回查拿到的可能是另一轮分析的结论，
     * 「一次选择 = 一份冻结输入」就不成立了。
     */
    private static EditorialSelection.Opportunity opportunityOf(HeronDataOpportunity opportunity) {
        return new EditorialSelection.Opportunity(
            opportunity.getId(),
            opportunity.getSourceKey(),
            opportunity.getHeadline(),
            opportunity.getSignals(),
            opportunity.getWindowPoints() == null ? 0 : opportunity.getWindowPoints(),
            snapshotSha(opportunity.getSnapshotJson()),
            opportunity.getShape(),
            opportunity.getCadence(),
            opportunity.getMetric());
    }

    /** 机会快照里记的哈希：选题当时看到的那份数据的指纹。 */
    private static String snapshotSha(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return "";
        }
        try {
            return JSON.readTree(snapshotJson).path("data_sha256").asString("").strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 解说线的「有正文」判定：证据里有原文链接且不是纯选题线索才算，与前端卡片口径一致。 */
    public static boolean hasBody(HeronNewsCandidate candidate) {
        return !evidenceIds(candidate).isEmpty();
    }

    private static List<String> evidenceIds(HeronNewsCandidate candidate) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode items = JSON.readTree(candidate.getEvidence()).path("items");
            for (JsonNode item : items) {
                if (item.path("topicOnly").asBoolean(false)) continue;
                String url = item.path("sourceUrl").asString("");
                if (!url.isBlank()) {
                    String id = item.path("sourceItemId").asString("");
                    ids.add(id.isBlank() ? url : id);
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return ids;
    }

    /** DatasetRouter 要 run 才能记用量；这里用 0 任务号，只做纯决策。 */
    private static ProductionRunVo decisionRun() {
        return new ProductionRunVo(NO_TASK, ProductionBizType.news_explainer, 0L, 0L, 0, "line-editor",
            "{}");
    }

    /** 路由只需要 limit 与标题上下文。 */
    private static JsonNode decisionContext() {
        return decisionContext("");
    }

    /**
     * 总编辑意图进选源上下文：不带它，选源器只看到「数据看板」四个字，
     * 总编辑定的方向（如今日做 geo）在选源这一步就丢了——三步各选一次、结果互相不认识。
     */
    private static JsonNode decisionContext(String intent) {
        String title = intent == null || intent.isBlank() ? "数据看板" : intent;
        return JSON.createObjectNode().put("limit", 30).put("title", title);
    }
}
