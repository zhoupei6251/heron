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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** 解说线：依据是证据片段，不是已验证事实，规则完全不同，不能共用一份提示词。 */
    private static final String SYSTEM_EVIDENCE = resource("/prompt/review/evidence-review.md");
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR");
    /** 核心断言的证据状态：核得住 / 核不了 / 没依据。只有第一种能通过。 */
    private static final Set<String> CORE_FACTS = Set.of("supported", "unverifiable", "unsupported");

    private final OpenAiModelClient model;

    public record Issue(String severity, String segmentId, String description, String suggestion) {
        boolean blocking() {
            return !"MINOR".equals(severity);
        }
    }

    /**
     * @param coreFacts 核心断言能否被证据支持；只有解说线（依据是证据片段的）才要求给
     */
    public record Verdict(String result, String coreFacts, List<Issue> issues, List<String> minorSuggestions) {
        /** BLOCKER 与 MAJOR 必须改；MINOR 只记录。 */
        public boolean blocked() {
            return issues.stream().anyMatch(Issue::blocking);
        }

        /**
         * 失败是不是「证据不够」而不是「文案跑在证据前面」。
         *
         * <p>这两类失败的自救方式完全不同：文案问题改稿能救，证据问题改稿救不了——
         * 把核不住的断言删掉只会得到一条没有信息量的稿子，那不是自愈，是降级凑数。
         * 数据看板线的事实由 Java 给，恒为 false。
         */
        public boolean evidenceProblem() {
            return coreFacts != null && !"supported".equals(coreFacts);
        }

        /** 证据问题里更细的一档：片段提到了相关的事但核不住。早停只认这一种。 */
        public boolean unverifiable() {
            return "unverifiable".equals(coreFacts);
        }

        /** 审稿记录落盘用；repairAttempts 区分一次通过和修出来的通过。 */
        public String toRecord(int repairAttempts, String promptVersion, LocalDateTime reviewedAt) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("schemaVersion", "1.0");
            record.put("result", result);
            if (coreFacts != null && !coreFacts.isBlank()) {
                record.put("coreFacts", coreFacts);
            }
            record.put("issues", issues.stream().map(issue -> Map.of(
                "severity", issue.severity(),
                "segmentId", issue.segmentId(),
                "description", issue.description(),
                "suggestion", issue.suggestion())).toList());
            record.put("minorSuggestions", minorSuggestions);
            record.put("repairAttempts", repairAttempts);
            record.put("promptVersion", promptVersion);
            record.put("reviewedAt", reviewedAt.toString());
            return JSON.writeValueAsString(record);
        }

        public List<String> blockingNotes() {
            return issues.stream().filter(Issue::blocking)
                .map(issue -> issue.segmentId() + "：" + issue.description()
                    + (issue.suggestion().isBlank() ? "" : "；建议 " + issue.suggestion()))
                .toList();
        }
    }

    public Verdict review(ProductionRunVo run, String scriptJson, String factsJson) {
        // 事实清单由 Java 给，不存在「核不了」，不要求 coreFacts
        return plan(model.chat(run, "editorial-review", SYSTEM, prompt(scriptJson, factsJson)), false);
    }

    /** 解说线审稿：依据是抓到的证据片段，审「有没有超出原文说过的范围」。 */
    public Verdict reviewEvidence(ProductionRunVo run, String scriptJson, String evidenceJson) {
        return plan(model.chat(run, "evidence-review", SYSTEM_EVIDENCE, evidencePrompt(scriptJson, evidenceJson)),
            true);
    }

    /**
     * 默认按「事实清单由 Java 给」的语义解析，不要求 coreFacts。
     * 解说线（依据是证据片段）必须用两参版本。
     */
    static Verdict plan(String reply) {
        return plan(reply, false);
    }

    /**
     * @param requireCoreFacts 解说线要求模型明确回答「核心断言核得了吗」。
     *                         只靠 issue 的 severity 判不住它——模型可以一边说「无法核实」
     *                         一边把 severity 打成 MINOR，下游看到的就是一条干净的 PASS。
     */
    static Verdict plan(String reply, boolean requireCoreFacts) {
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
        String coreFacts = node.path("coreFacts").asString("").strip();
        if (requireCoreFacts && !CORE_FACTS.contains(coreFacts)) {
            throw invalid("解说线审稿必须给出 coreFacts（supported/unverifiable/unsupported），收到："
                + (coreFacts.isBlank() ? "（空）" : coreFacts));
        }
        if (!coreFacts.isBlank() && !CORE_FACTS.contains(coreFacts)) {
            throw invalid("未知 coreFacts：" + coreFacts);
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
        if ("FAIL".equals(result) && issues.stream().noneMatch(Issue::blocking)) {
            // FAIL 却只有 MINOR：模型说了不通过，blocked() 却会放行，这是矛盾输出，拒收
            throw invalid("FAIL 必须至少给出一条 BLOCKER 或 MAJOR");
        }
        if ("PASS".equals(result) && issues.stream().anyMatch(Issue::blocking)) {
            throw invalid("PASS 不能带 BLOCKER/MAJOR");
        }
        if (requireCoreFacts) {
            // 先判 PASS：核心断言没被支持却判通过，是最该拦住的那条，提示也要最直接
            if ("PASS".equals(result) && !"supported".equals(coreFacts)) {
                throw invalid("核心断言未经证据支持（coreFacts=" + coreFacts + "），不能判 PASS");
            }
            long blockers = issues.stream().filter(issue -> "BLOCKER".equals(issue.severity())).count();
            if ("unsupported".equals(coreFacts) && blockers == 0) {
                throw invalid("coreFacts=unsupported 必须至少给出一条 BLOCKER");
            }
            if ("unverifiable".equals(coreFacts) && issues.stream().noneMatch(Issue::blocking)) {
                throw invalid("coreFacts=unverifiable 必须至少给出一条 MAJOR");
            }
        }
        return new Verdict(result, coreFacts.isBlank() ? null : coreFacts, issues, minors);
    }

    static String prompt(String scriptJson, String factsJson) {
        return "已被 Java 验证的事实：\n" + factsJson
            + "\n\n待审文案：\n" + scriptJson
            + "\n\n按契约输出 JSON。";
    }

    static String evidencePrompt(String scriptJson, String evidenceJson) {
        return "抓取到的原文证据片段（只有片段，不是全文）：\n" + evidenceJson
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
