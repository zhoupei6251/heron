package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.EditorialCandidatePool;
import org.dromara.heron.domain.EditorialChoice;
import org.dromara.heron.domain.EditorialSelection;
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
     * @param focusCandidateId 总编辑锁定的候选，可为空；仅解说线生效
     */
    public Optional<EditorialChoice> select(String line, EditorialCandidatePool pool, String intent,
                                            Long focusCandidateId) {
        boolean dataBoard = EditorialDecisionService.DATA_BOARD.equals(line);
        Optional<EditorialSelection> first = dataBoard
            ? editDataBoard(intent, pool.datasets())
            : editExplainer(pool.articles(), intent, focusCandidateId);
        if (first.isPresent()) {
            return Optional.of(new EditorialChoice(first.get(), line, false, null));
        }
        String reason = dataBoard ? "data_board_empty" : "explainer_empty";
        Optional<EditorialSelection> second = dataBoard
            ? editExplainer(pool.articles(), intent, focusCandidateId)
            : editDataBoard(intent, pool.datasets());
        return second.map(selection -> new EditorialChoice(selection, line, true, reason));
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
     */
    public Optional<EditorialSelection> editDataBoard(String intent, List<HeronDataSource> datasets) {
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
        String template = templatePicker.pick(name, "数据看板", EditorialDecisionService.DATA_BOARD);
        if (template == null) {
            return Optional.empty();
        }
        return Optional.of(new EditorialSelection(EditorialDecisionService.DATA_BOARD,
            null, route.sourceKey(), route.digest(), template, intent, List.of()));
    }

    /** 解说线的「有正文」判定：证据里有原文链接就算，与前端卡片口径一致。 */
    public static boolean hasBody(HeronNewsCandidate candidate) {
        return !evidenceIds(candidate).isEmpty();
    }

    private static List<String> evidenceIds(HeronNewsCandidate candidate) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode items = JSON.readTree(candidate.getEvidence()).path("items");
            for (JsonNode item : items) {
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
