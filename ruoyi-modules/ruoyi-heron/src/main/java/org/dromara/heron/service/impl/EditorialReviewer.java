package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 独立审稿：只审文案有没有把已验证的事实讲对，不重算数据。
 * 事实清单由 Java 给，模型没有独立信源，重新计算只会得到自我确认。
 */
@Service
@RequiredArgsConstructor
public class EditorialReviewer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/review/editorial-review.md");
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR");

    private final OpenAiModelClient model;

    public record Issue(String severity, String segmentId, String description, String suggestion) {
        boolean blocking() {
            return !"MINOR".equals(severity);
        }
    }

    public record Verdict(String result, List<Issue> issues, List<String> minorSuggestions) {
        /** BLOCKER 与 MAJOR 必须改；MINOR 只记录。 */
        public boolean blocked() {
            return issues.stream().anyMatch(Issue::blocking);
        }

        public List<String> blockingNotes() {
            return issues.stream().filter(Issue::blocking)
                .map(issue -> issue.segmentId() + "：" + issue.description()
                    + (issue.suggestion().isBlank() ? "" : "；建议 " + issue.suggestion()))
                .toList();
        }
    }

    public Verdict review(ProductionRunVo run, String scriptJson, String factsJson) {
        return plan(model.chat(run, "editorial-review", SYSTEM, prompt(scriptJson, factsJson)));
    }

    static Verdict plan(String reply) {
        JsonNode node;
        try {
            node = reply == null ? null : JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw invalid("审稿输出不是 JSON");
        }
        if (node == null || !node.isObject()) throw invalid("审稿输出不是 JSON 对象");
        String result = node.path("result").asString("").strip();
        if (!"PASS".equals(result) && !"FAIL".equals(result)) {
            throw invalid("未知 result：" + result);
        }
        List<Issue> issues = new ArrayList<>();
        JsonNode list = node.path("issues");
        if (list.isArray()) {
            for (JsonNode item : list) {
                String severity = item.path("severity").asString("").strip();
                if (!SEVERITIES.contains(severity)) throw invalid("未知 severity：" + severity);
                issues.add(new Issue(severity,
                    item.path("segmentId").asString("").strip(),
                    item.path("description").asString("").strip(),
                    item.path("suggestion").asString("").strip()));
            }
        }
        List<String> minors = new ArrayList<>();
        node.path("minorSuggestions").forEach(item -> {
            String text = item.asString("").strip();
            if (!text.isEmpty()) minors.add(text);
        });
        if ("FAIL".equals(result) && issues.isEmpty()) {
            throw invalid("FAIL 必须给出至少一条 issue");
        }
        if ("PASS".equals(result) && issues.stream().anyMatch(Issue::blocking)) {
            throw invalid("PASS 不能带 BLOCKER/MAJOR");
        }
        return new Verdict(result, issues, minors);
    }

    static String prompt(String scriptJson, String factsJson) {
        return "已被 Java 验证的事实：\n" + factsJson
            + "\n\n待审文案：\n" + scriptJson
            + "\n\n按契约输出 JSON。";
    }

    private static HeronBusinessException invalid(String reason) {
        return new HeronBusinessException(409, "editorial_review_invalid", reason);
    }

    private static String resource(String path) {
        try (InputStream stream = EditorialReviewer.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
