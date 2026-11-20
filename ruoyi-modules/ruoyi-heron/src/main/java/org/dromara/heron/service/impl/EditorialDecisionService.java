package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 每日总编辑：只决定今天走哪条线，或今天不发。
 * 只给候选概况，不给全量条目——选具体素材是线内编辑的事。
 *
 * <p>候选摘要带 ID：总编辑如果说「就做这条」，那条候选必须能在池子里精确对上，
 * 不能靠标题文本对齐（标题会改，重复标题也存在）。
 */
@Service
@RequiredArgsConstructor
public class EditorialDecisionService {

    public static final String DATA_BOARD = "DATA_BOARD";
    public static final String NEWS_EXPLAINER = "NEWS_EXPLAINER";
    /** 趋势洞察线：数据看板讲发生了什么，这条线讲变化意味着什么。 */
    public static final String TREND_INSIGHT = "TREND_INSIGHT";
    public static final String NO_PUBLISH = "NO_PUBLISH";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/editorial/daily-editor.md");
    private static final Set<String> LINES = Set.of(DATA_BOARD, TREND_INSIGHT, NEWS_EXPLAINER);

    private final OpenAiModelClient model;

    /** 候选摘要：id 是给校验用的，label 是给模型看的。 */
    public record CandidateRef(long candidateId, String label) { }

    /** 数据集摘要：sourceKey 是给校验用的。 */
    public record DatasetRef(String sourceKey, String label) { }

    /** 数据机会摘要：id 给校验用，label 必须带数值——没有数字总编辑判断不了值不值得做。 */
    public record OpportunityRef(long opportunityId, String label) { }

    /**
     * 两条线的候选概况。热榜侧只有标题，正文侧只有带正文的文章——这个差别由代码保证，
     * 模型不能凭标题去选需要正文的线。poolId/poolHash 让决策与选择可对齐到同一份快照。
     *
     * <p>lockedScopes 是人工锁定的维度：它进提示词，但只有锁本身（作用域与理由），
     * 不带任何表现数据。总编辑若仍要走被锁的方向，必须在 reason 里说明——
     * 人可以违反锁，但不能让违反看起来像是没有锁。
     */
    public record Pools(String poolId, String poolHash,
                        List<CandidateRef> hot, List<CandidateRef> articles,
                        List<DatasetRef> datasets, List<OpportunityRef> opportunities,
                        List<String> recentPublications,
                        List<LockedScope> lockedScopes) {

        /** 老入口：没有数据机会时的摘要。 */
        public Pools(String poolId, String poolHash, List<CandidateRef> hot, List<CandidateRef> articles,
                     List<DatasetRef> datasets, List<String> recentPublications) {
            this(poolId, poolHash, hot, articles, datasets, List.of(), recentPublications, List.of());
        }

        /** 没有锁定时的入口。 */
        public Pools(String poolId, String poolHash, List<CandidateRef> hot, List<CandidateRef> articles,
                     List<DatasetRef> datasets, List<OpportunityRef> opportunities,
                     List<String> recentPublications) {
            this(poolId, poolHash, hot, articles, datasets, opportunities, recentPublications, List.of());
        }
    }

    /** 一个人工锁定的维度。 */
    public record LockedScope(String scope, String reason, String scopeUntil) {
    }

    /**
     * focusOpportunityId 与 focusCandidateId 一样是可选的锚点：
     * 总编辑不指定时由线内编辑自己挑。
     */
    public record Decision(String decision, String selectedLine, String reason, String editorialIntent,
                           Long focusCandidateId, Long focusOpportunityId) {

        /** 老入口：没有锁定数据机会。 */
        public Decision(String decision, String selectedLine, String reason, String editorialIntent,
                        Long focusCandidateId) {
            this(decision, selectedLine, reason, editorialIntent, focusCandidateId, null);
        }

        public boolean publish() {
            return "PUBLISH".equals(decision);
        }
    }

    public Decision decide(Pools pools) {
        // 决策发生在任何 run 之前，用量记录挂不上 run；这里走不带用量的调用
        return plan(model.chatWithoutUsage(SYSTEM, prompt(pools)), pools);
    }

    /** 纯决策：没素材的线不许选，这是边界不是权重。 */
    static Decision plan(String reply, Pools pools) {
        JsonNode node;
        try {
            node = reply == null ? null : JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw invalid("总编辑输出不是 JSON");
        }
        if (node == null || !node.isObject()) {
            throw invalid("总编辑输出不是 JSON 对象");
        }
        String decision = node.path("decision").asString("").strip();
        String reason = node.path("reason").asString("").strip();
        if (NO_PUBLISH.equals(decision)) {
            if (reason.isBlank()) throw invalid("NO_PUBLISH 必须给出 reason");
            return new Decision(NO_PUBLISH, "", reason, "", null, null);
        }
        if (!"PUBLISH".equals(decision)) {
            throw invalid("未知 decision：" + decision);
        }
        String line = node.path("selectedLine").asString("").strip();
        if (!LINES.contains(line)) {
            throw invalid("未知 selectedLine：" + line);
        }
        if (reason.isBlank()) throw invalid("PUBLISH 必须给出 reason");
        String intent = node.path("editorialIntent").asString("").strip();
        if (intent.isBlank()) throw invalid("PUBLISH 必须给出 editorialIntent");
        if (DATA_BOARD.equals(line) && pools.opportunities().isEmpty() && pools.datasets().isEmpty()) {
            throw poolEmpty("数据看板线既没有数据机会也没有可用数据集");
        }
        if (TREND_INSIGHT.equals(line) && pools.opportunities().isEmpty() && pools.datasets().isEmpty()) {
            throw poolEmpty("趋势洞察线既没有数据机会也没有可用数据集");
        }
        if (NEWS_EXPLAINER.equals(line) && pools.articles().isEmpty()) {
            throw poolEmpty("解说线没有带正文的文章");
        }
        Long focus = focus(node, pools);
        Long focusOpportunity = focusOpportunity(node, pools);
        return new Decision("PUBLISH", line, reason, intent, focus, focusOpportunity);
    }

    /** 锁定的候选必须真的在池子里；对不上就是模型编了一个 ID，不能猜。 */
    private static Long focus(JsonNode node, Pools pools) {
        JsonNode raw = node.path("focusCandidateId");
        if (raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        long id = raw.asLong(0L);
        boolean known = pools.hot().stream().anyMatch(ref -> ref.candidateId() == id)
            || pools.articles().stream().anyMatch(ref -> ref.candidateId() == id);
        if (id == 0L || !known) {
            throw invalid("focusCandidateId 不在候选池里：" + raw);
        }
        return id;
    }

    /** 机会 ID 同样必须对得上：编一个不存在的 ID 等于让线内编辑去猜。 */
    private static Long focusOpportunity(JsonNode node, Pools pools) {
        JsonNode raw = node.path("focusOpportunityId");
        if (raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        long id = raw.asLong(0L);
        boolean known = pools.opportunities().stream().anyMatch(ref -> ref.opportunityId() == id);
        if (id == 0L || !known) {
            throw invalid("focusOpportunityId 不在候选池里：" + raw);
        }
        return id;
    }

    static String prompt(Pools pools) {
        StringBuilder text = new StringBuilder("今天的候选概况：\n");
        append(text, "热榜标题", pools.hot());
        append(text, "带正文的文章", pools.articles());
        appendDatasets(text, "可用数据集", pools.datasets());
        append(text, "数据机会（带分数与取数口径，分数越高越值得做）", pools.opportunities());
        text.append("形态为 wide、点数充足且信号里出现连续方向的机会，优先考虑 TREND_INSIGHT；\n")
            .append("只是单点异常或形态不是 wide 的，仍走 DATA_BOARD。\n");
        append(text, "最近已发布", pools.recentPublications());
        // 锁进提示词，但只有锁本身（作用域与理由），不带表现数据。
        // 表现数据一旦进提示词，模型就有了策略倾向，那正是「用印象代替证据」。
        if (!pools.lockedScopes().isEmpty()) {
            text.append("\n以下维度已被人工锁定（只作提示，不阻止你选择）：\n");
            for (LockedScope scope : pools.lockedScopes()) {
                text.append("- ").append(scope.scope()).append("：").append(scope.reason())
                    .append("（至 ").append(scope.scopeUntil()).append("）\n");
            }
            text.append("如果你仍要选被锁定的方向，必须在 reason 里说明为什么。\n");
        }
        return text.append("按契约输出 JSON。").toString();
    }

    private static void append(StringBuilder text, String label, List<?> items) {
        text.append("\n【").append(label).append("】共 ").append(items.size()).append(" 条\n");
        for (Object item : items) {
            if (item instanceof CandidateRef ref) {
                text.append("- [").append(ref.candidateId()).append("] ").append(ref.label()).append('\n');
            } else if (item instanceof OpportunityRef ref) {
                text.append("- [").append(ref.opportunityId()).append("] ").append(ref.label()).append('\n');
            } else {
                text.append("- ").append(item).append('\n');
            }
        }
    }

    private static void appendDatasets(StringBuilder text, String label, List<DatasetRef> items) {
        text.append("\n【").append(label).append("】共 ").append(items.size()).append(" 条\n");
        for (DatasetRef ref : items) {
            text.append("- ").append(ref.sourceKey()).append(" | ").append(ref.label()).append('\n');
        }
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(409, "editorial_decision_invalid", reason);
    }

    private static HeronBusinessException poolEmpty(String reason) {
        return new HeronBusinessException(409, "editorial_pool_empty", reason);
    }

    private static String resource(String path) {
        try (InputStream stream = EditorialDecisionService.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
