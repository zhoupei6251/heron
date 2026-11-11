package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.dromara.heron.service.INewsMetricService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 选题抽卡：把候选交给模型按《选题抽卡规则》挑一条。
 * 模型不可用、超时或答非所问一律返回空，由调用方退回热度排序——筛选题不能成为产出的单点故障。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewsCandidateScreener {
    /** 一次只看前 N 条：太多会稀释判断，太少会错过冷门但好的选题。 */
    private static final int SCREEN_LIMIT = 10;
    private static final int SUMMARY_LIMIT = 120;
    private static final int PERFORMANCE_LIMIT = 5;
    // 用独立 JsonMapper 而不是 JsonUtils：后者绑定 Spring 容器，纯单元测试里初始化会失败
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/heron/news-selection-prompt.md");

    private final ObjectProvider<OpenAiModelClient> modelProvider;
    private final INewsMetricService metrics;

    public Optional<Long> screen(List<HeronNewsCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0).getCandidateId());
        }
        OpenAiModelClient model = modelProvider.getIfAvailable();
        if (model == null) {
            return Optional.empty();
        }
        List<HeronNewsCandidate> pool = candidates.stream().limit(SCREEN_LIMIT).toList();
        try {
            Long picked = choose(pool.stream().map(HeronNewsCandidate::getCandidateId).toList(),
                model.chatWithoutUsage(SYSTEM, prompt(pool, recentPerformance())));
            return Optional.ofNullable(picked);
        } catch (RuntimeException e) {
            log.warn("选题抽卡失败，退回热度排序: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 表现数据是参考项，读失败不影响选题；没有数据就退化成纯规则判断。 */
    private List<HeronNewsMetricMapper.Performance> recentPerformance() {
        if (metrics == null) {
            return List.of();
        }
        try {
            return metrics.recentPerformance(PERFORMANCE_LIMIT);
        } catch (RuntimeException e) {
            log.warn("读取历史表现失败，按无数据处理: {}", e.getMessage());
            return List.of();
        }
    }

    /** 只接受候选列表里真实存在的 id；0 表示"今天没有值得做的"。 */
    static Long choose(List<Long> ids, String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String text = reply.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text.substring(start, end + 1));
            long id = node.path("candidate_id").asLong(0);
            return id > 0 && ids.contains(id) ? id : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String prompt(List<HeronNewsCandidate> pool, List<HeronNewsMetricMapper.Performance> performance) {
        StringBuilder text = new StringBuilder();
        if (!performance.isEmpty()) {
            text.append("最近几期的实际表现（完播率越高说明选题越能留人，可据此判断同类选题）：\n");
            for (HeronNewsMetricMapper.Performance item : performance) {
                text.append("- ").append(item.title())
                    .append(" 播放 ").append(item.plays() == null ? "未知" : item.plays())
                    .append(" / 完播 ").append(item.completionRate() == null ? "未知"
                        : item.completionRate().multiply(java.math.BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP) + "%")
                    .append('\n');
            }
            text.append('\n');
        }
        text.append("候选列表：\n");
        for (HeronNewsCandidate candidate : pool) {
            text.append("- id=").append(candidate.getCandidateId())
                .append(" 热度=").append(candidate.getHeatScore() == null ? 0 : candidate.getHeatScore())
                .append(" 标题：").append(candidate.getTitle()).append('\n')
                .append("  摘要：").append(trim(candidate.getSummary())).append('\n');
        }
        text.append("按规则只选一条，输出 JSON。");
        return text.toString();
    }

    private static String trim(String summary) {
        if (summary == null) {
            return "";
        }
        String flat = summary.replaceAll("\\s+", " ").strip();
        return flat.length() <= SUMMARY_LIMIT ? flat : flat.substring(0, SUMMARY_LIMIT) + "…";
    }

    private static String resource(String path) {
        try (InputStream stream = NewsCandidateScreener.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少选题提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("选题提示词不可读 " + path, e);
        }
    }
}
