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
 */
@Service
@RequiredArgsConstructor
public class EditorialDecisionService {

    public static final String DATA_BOARD = "DATA_BOARD";
    public static final String NEWS_EXPLAINER = "NEWS_EXPLAINER";
    public static final String NO_PUBLISH = "NO_PUBLISH";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/editorial/daily-editor.md");
    private static final Set<String> LINES = Set.of(DATA_BOARD, NEWS_EXPLAINER);

    private final OpenAiModelClient model;

    /**
     * 两条线的候选概况。热榜侧只有标题，正文侧只有带正文的文章——这个差别由代码保证，
     * 模型不能凭标题去选需要正文的线。
     */
    public record Pools(List<String> hotHeadlines, List<String> articleCandidates,
                        List<String> dataSources, List<String> recentPublications) { }

    public record Decision(String decision, String selectedLine, String reason, String editorialIntent) {
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
            return new Decision(NO_PUBLISH, "", reason, "");
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
        if (DATA_BOARD.equals(line) && pools.dataSources().isEmpty()) {
            throw poolEmpty("数据看板线没有可用数据集");
        }
        if (NEWS_EXPLAINER.equals(line) && pools.articleCandidates().isEmpty()) {
            throw poolEmpty("解说线没有带正文的文章");
        }
        return new Decision("PUBLISH", line, reason, intent);
    }

    static String prompt(Pools pools) {
        StringBuilder text = new StringBuilder("今天的候选概况：\n");
        append(text, "热榜标题", pools.hotHeadlines());
        append(text, "带正文的文章", pools.articleCandidates());
        append(text, "可用数据集", pools.dataSources());
        append(text, "最近已发布", pools.recentPublications());
        return text.append("按契约输出 JSON。").toString();
    }

    private static void append(StringBuilder text, String label, List<String> items) {
        text.append("\n【").append(label).append("】共 ").append(items.size()).append(" 条\n");
        for (String item : items) {
            text.append("- ").append(item).append('\n');
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
