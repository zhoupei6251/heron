package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.EditorialSelection;
import org.dromara.heron.domain.HeronDataSource;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
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

    private final NewsCandidateScreener screener;
    private final NewsTemplatePicker templatePicker;
    private final DatasetRouter datasetRouter;
    private final HeronDataSourceMapper dataSourceMapper;
    private final HeronNewsCandidateMapper candidateMapper;

    private static final int POOL_LIMIT = 50;

    /**
     * 总编辑定线之后的唯一入口。选不到就回退另一条线再试一次——
     * 日更账号直接弃更的代价比多调一次模型高得多。
     */
    public Optional<EditorialSelection> select(String line, EditorialDecisionService.Pools pools, String intent) {
        boolean dataBoard = EditorialDecisionService.DATA_BOARD.equals(line);
        Optional<EditorialSelection> first = dataBoard
            ? editDataBoard(intent)
            : editExplainer(autoCandidates(), intent);
        if (first.isPresent()) {
            return first;
        }
        return dataBoard
            ? editExplainer(autoCandidates(), intent)
            : editDataBoard(intent);
    }

    /** 解说线候选：24 小时内未发布且证据里带原文链接的候选。 */
    private List<HeronNewsCandidate> autoCandidates() {
        return candidateMapper.selectList(QueryBuilder.lambda(HeronNewsCandidate.class)
            .isNull(HeronNewsCandidate::getPublishedAt)
            .isNull(HeronNewsCandidate::getClaimedBy)
            .isNull(HeronNewsCandidate::getActiveTaskId)
            .ge(HeronNewsCandidate::getLastSeenAt, LocalDateTime.now().minusHours(24))
            .orderByDesc(HeronNewsCandidate::getHeatScore)
            .last("LIMIT " + POOL_LIMIT).build());
    }

    /**
     * 解说线：在「有正文」的候选里选一条，再选版式。
     * 空结果表示这条线今天没有能做的素材，由调用方回退到另一条线。
     */
    public Optional<EditorialSelection> editExplainer(List<HeronNewsCandidate> pool, String intent) {
        List<HeronNewsCandidate> withBody = pool.stream()
            .filter(candidate -> !evidenceIds(candidate).isEmpty()).toList();
        if (withBody.isEmpty()) {
            return Optional.empty();
        }
        Long candidateId = screener.screen(withBody).orElse(withBody.get(0).getCandidateId());
        HeronNewsCandidate candidate = withBody.stream()
            .filter(item -> candidateId.equals(item.getCandidateId())).findFirst().orElse(withBody.get(0));
        String template = templatePicker.pick(candidate.getTitle(), candidate.getSummary(),
            EditorialDecisionService.NEWS_EXPLAINER);
        if (template == null) {
            // 没有解说线模板就不该硬走这条线；交给调用方回退
            return Optional.empty();
        }
        return Optional.of(new EditorialSelection(EditorialDecisionService.NEWS_EXPLAINER,
            candidate.getCandidateId(), null, false, template, intent, evidenceIds(candidate)));
    }

    /**
     * 数据看板线：选数据源 + 版式。数据源由 DatasetRouter 在「已启用、探测 ok、7 天未用」里挑。
     * 没有可用数据源是正常结果，交给调用方回退到解说线。
     */
    public Optional<EditorialSelection> editDataBoard(String intent) {
        DatasetRouter.Route route;
        try {
            route = datasetRouter.route(decisionRun(), decisionContext());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        HeronDataSource source = dataSourceMapper.selectByKey(route.sourceKey());
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
            JsonNode items = JsonUtils.getJsonMapper().readTree(candidate.getEvidence()).path("items");
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
        return JsonUtils.getJsonMapper().createObjectNode().put("limit", 30).put("title", "数据看板");
    }
}
