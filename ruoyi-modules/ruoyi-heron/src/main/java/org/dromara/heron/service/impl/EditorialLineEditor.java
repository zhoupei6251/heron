package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialSelection;
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
 */
@Service
@RequiredArgsConstructor
public class EditorialLineEditor {

    /** 线内编辑不在生产任务里，用量用 0 任务号走纯决策调用。 */
    private static final long NO_TASK = 0L;
    /** 本地 mapper：证据解析与上下文构造要能在纯单测里跑，不依赖 Spring 上下文。 */
    private static final tools.jackson.databind.json.JsonMapper JSON =
        tools.jackson.databind.json.JsonMapper.builder().build();

    private final NewsCandidateScreener screener;
    private final NewsTemplatePicker templatePicker;
    private final DatasetRouter datasetRouter;

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
        if (EditorialDecisionService.KNOWLEDGE_EXPLAINER.equals(line)) {
            Optional<EditorialSelection> knowledge = editKnowledge(pool.knowledge(), intent, focusCandidateId);
            if (knowledge.isPresent()) {
                return Optional.of(new EditorialChoice(knowledge.get(), line, false, null));
            }
            // 备用线也会空：池子里的知识候选都在重试中被剔掉时，按既定顺序回退一次
            Optional<EditorialSelection> second = editExplainer(pool.articles(), intent, focusCandidateId)
                .or(() -> editDatasetLine(EditorialDecisionService.DATA_BOARD,
                    pool.datasets(), pool.opportunities(), focusOpportunityId));
            return second.map(selection -> new EditorialChoice(selection, line, true, "knowledge_empty"));
        }
        boolean trend = EditorialDecisionService.TREND_INSIGHT.equals(line);
        boolean datasetLine = trend || EditorialDecisionService.DATA_BOARD.equals(line);
        Optional<EditorialSelection> first = datasetLine
            ? editDatasetLine(trend ? EditorialDecisionService.TREND_INSIGHT : EditorialDecisionService.DATA_BOARD,
                pool.datasets(), pool.opportunities(), focusOpportunityId)
            : editExplainer(pool.articles(), intent, focusCandidateId);
        if (first.isPresent()) {
            return Optional.of(new EditorialChoice(first.get(), line, false, null));
        }
        String reason = trend ? "trend_empty" : datasetLine ? "data_board_empty" : "explainer_empty";
        Optional<EditorialSelection> second = datasetLine
            ? editExplainer(pool.articles(), intent, focusCandidateId)
            : editDatasetLine(EditorialDecisionService.DATA_BOARD,
                pool.datasets(), pool.opportunities(), focusOpportunityId);
        if (second.isPresent()) {
            return second.map(selection -> new EditorialChoice(selection, line, true, reason));
        }
        // 知识线是最后一条：其余线当天都没素材时，它也只剩这一条路
        return editKnowledge(pool.knowledge(), intent, focusCandidateId)
            .map(selection -> new EditorialChoice(selection, line, true, reason + "_knowledge"));
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
            .filter(item -> focusCandidateId.equals(item.getCandidateId())).findFirst().orElse(null);
        if (candidate == null) {
            Long candidateId = screener.screen(usable).orElse(usable.get(0).getCandidateId());
            Long picked = candidateId;
            candidate = usable.stream()
                .filter(item -> picked.equals(item.getCandidateId())).findFirst().orElse(usable.get(0));
        }
        HeronNewsCandidate chosen = candidate;
        String template = templatePicker.pick(chosen.getTitle(), chosen.getSummary(),
            EditorialDecisionService.KNOWLEDGE_EXPLAINER);
        if (template == null) {
            return Optional.empty();
        }
        return Optional.of(new EditorialSelection(EditorialDecisionService.KNOWLEDGE_EXPLAINER,
            chosen.getCandidateId(), chosen.getSourceKey(), false, template, intent, evidenceIds(chosen)));
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
            .filter(item -> focusCandidateId.equals(item.getCandidateId())).findFirst().orElse(null);
        if (candidate == null) {
            Long candidateId = screener.screen(withBody).orElse(withBody.get(0).getCandidateId());
            Long picked = candidateId;
            candidate = withBody.stream()
                .filter(item -> picked.equals(item.getCandidateId())).findFirst().orElse(withBody.get(0));
        }
        HeronNewsCandidate chosen = candidate;
        String template = templatePicker.pick(chosen.getTitle(), chosen.getSummary(),
            EditorialDecisionService.NEWS_EXPLAINER);
        if (template == null) {
            // 没有解说线模板就不该硬走这条线；交给调用方回退
            return Optional.empty();
        }
        return Optional.of(new EditorialSelection(EditorialDecisionService.NEWS_EXPLAINER,
            chosen.getCandidateId(), null, false, template, intent, evidenceIds(chosen)));
    }

    /**
     * 数据看板线：从**建池时冻结的**数据源候选里挑一条 + 选版式。
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
        boolean trend = EditorialDecisionService.TREND_INSIGHT.equals(line);
        List<HeronDataOpportunity> usable = opportunities == null ? List.of()
            : trend ? opportunities.stream().filter(item -> TrendAnalysis.routable(opportunityOf(item))).toList()
            : opportunities;
        HeronDataOpportunity focused = focusOpportunityId == null ? null
            : usable.stream()
                .filter(item -> focusOpportunityId.equals(item.getOpportunityId()))
                .findFirst().orElse(null);
        if (focused == null && !usable.isEmpty()) {
            // 没锁定就用分数最高的那条：选源器拿到的是写死的题材，不可能比算出来的机会更懂今天
            focused = usable.get(0);
        }
        if (focused != null) {
            HeronDataOpportunity chosen = focused;
            String sourceName = datasets == null ? null : datasets.stream()
                .filter(item -> item.getSourceKey().equals(chosen.getSourceKey()))
                .map(HeronDataSource::getName).findFirst().orElse(null);
            String name = sourceName == null ? chosen.getHeadline() : sourceName;
            String template = templatePicker.pick(name, trend ? "趋势洞察" : "数据看板", line);
            if (template == null) {
                return Optional.empty();
            }
            // 故事角度由机会自己的信号决定。编辑意图已经在 editorialIntent 里，
            // 角度再抄一遍就没有第二个视角，写稿只能拿到一条「今天要讲它」的通用稿。
            DatasetStoryAngle.Angle angle = DatasetStoryAngle.pick(chosen.getShape(),
                signalsOf(chosen.getSignals()), chosen.getPeriodStart(), chosen.getPeriodEnd());
            // digest=false：这是被机会明确选中的源，不是「没题材时的速览」
            return Optional.of(new EditorialSelection(line,
                null, chosen.getSourceKey(), false, template, text(angle, trend), List.of(),
                chosen.getOpportunityId(), opportunityOf(chosen)));
        }
        if (datasets == null || datasets.isEmpty()) {
            return Optional.empty();
        }
        DatasetRouter.Route route;
        try {
            route = datasetRouter.pick(decisionRun(), decisionContext(), datasets);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        HeronDataSource source = datasets.stream()
            .filter(item -> item.getSourceKey().equals(route.sourceKey())).findFirst().orElse(null);
        String name = source == null ? route.sourceKey() : source.getName();
        String template = templatePicker.pick(name, trend ? "趋势洞察" : "数据看板", line);
        if (template == null) {
            return Optional.empty();
        }
        return Optional.of(new EditorialSelection(line,
            null, route.sourceKey(), route.digest(), template, text(DatasetStoryAngle.DIGEST_ANGLE, trend), List.of()));
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
            opportunity.getOpportunityId(),
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
        return new ProductionRunVo(NO_TASK, ProductionBizType.news_video, 0L, 0L, 0, "line-editor",
            "{}", null);
    }

    /** 路由只需要 limit 与标题上下文。 */
    private static JsonNode decisionContext() {
        return JSON.createObjectNode().put("limit", 30).put("title", "数据看板");
    }
}
