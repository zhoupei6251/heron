package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 选排版：一次轻量模型调用 + 系统硬约束。
 * 约束比模型更可靠——最近两期用过的模板一律排除，模型失败或答非所问就退回「最久未用」。
 */
@Service
@RequiredArgsConstructor
public class NewsTemplatePicker {
    private static final int RECENT_LIMIT = 5;
    private static final int BLOCK_LIMIT = 2;
    private static final int SCAN_LIMIT = 20;
    private static final String SYSTEM =
        "你是新闻短视频频道的排版编辑。根据标题与摘要，从给出的版式里选一个最合适的。只回复一个版式标识，不要解释。";

    private final NewsProductionDomain domain;
    private final ObjectProvider<OpenAiModelClient> modelProvider;
    private final HeronNewsWorkMapper workMapper;

    /** 手工认领：两条线的模板都进候选，由模型按标题摘要选。 */
    public String pick(String title, String summary) {
        return pickFrom(domain.templateCatalog(), title, summary, null);
    }

    /**
     * 按生产线收窄候选。判断依据是模板自己声明的 supportedLines，不是命名前缀——
     * 改模板名不该改出片逻辑。没写 supportedLines 的老模板才退回前缀推断。
     */
    public String pick(String title, String summary, String line) {
        return pickFrom(domain.templateCatalog().stream()
            .filter(item -> item.supports(line)).toList(), title, summary, line);
    }

    private String pickFrom(List<NewsProductionDomain.TemplateInfo> all, String title, String summary, String line) {
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() == 1) {
            return all.get(0).key();
        }
        List<String> recent = recentKeys();
        List<String> blocked = recent.stream().limit(BLOCK_LIMIT).toList();
        List<NewsProductionDomain.TemplateInfo> candidates = all.stream()
            .filter(item -> !blocked.contains(item.key())).toList();
        if (candidates.isEmpty()) {
            candidates = all;
        }
        String reply = null;
        OpenAiModelClient model = modelProvider.getIfAvailable();
        if (model != null) {
            try {
                reply = model.chatWithoutUsage(SYSTEM, prompt(title, summary, candidates, recent, line));
            } catch (RuntimeException e) {
                // 选择失败不阻塞认领：与模型不可用时的行为一致，退回最久未用
                reply = null;
            }
        }
        String chosen = choose(candidates.stream().map(NewsProductionDomain.TemplateInfo::key).toList(), reply);
        return chosen != null ? chosen : leastRecentlyUsed(candidates, recent);
    }

    /** 模型必须原样回一个候选标识；多一个字都算没选中。 */
    static String choose(List<String> candidates, String reply) {
        if (reply == null) {
            return null;
        }
        String trimmed = reply.strip();
        return candidates.contains(trimmed) ? trimmed : null;
    }

    /** 从没用过的优先；都用过就取最久没用的那套。 */
    static String leastRecentlyUsed(List<NewsProductionDomain.TemplateInfo> candidates, List<String> recent) {
        for (NewsProductionDomain.TemplateInfo item : candidates) {
            if (!recent.contains(item.key())) {
                return item.key();
            }
        }
        for (int index = recent.size() - 1; index >= 0; index--) {
            String key = recent.get(index);
            if (candidates.stream().anyMatch(item -> item.key().equals(key))) {
                return key;
            }
        }
        return candidates.get(0).key();
    }

    private List<String> recentKeys() {
        List<HeronNewsWork> works = workMapper.selectList(QueryBuilder.lambda(HeronNewsWork.class)
            .orderByDesc(HeronNewsWork::getCreateTime, HeronNewsWork::getWorkId).last("LIMIT " + SCAN_LIMIT).build());
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (HeronNewsWork work : works) {
            String key = work.getTemplateKey();
            if (key != null && !key.isBlank()) {
                keys.add(key);
            }
            if (keys.size() >= RECENT_LIMIT) {
                break;
            }
        }
        return List.copyOf(keys);
    }

    private static String prompt(String title, String summary, List<NewsProductionDomain.TemplateInfo> candidates,
                                 List<String> recent, String line) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("本期标题：").append(nullToEmpty(title)).append('\n');
        prompt.append("本期摘要：").append(nullToEmpty(summary)).append('\n');
        prompt.append("可用版式（标识 名称：说明）：\n");
        for (NewsProductionDomain.TemplateInfo item : candidates) {
            prompt.append("- ").append(item.key()).append(' ').append(item.name())
                .append('：').append(item.description()).append('\n');
        }
        if (!recent.isEmpty()) {
            prompt.append("最近已用过，不要选：").append(String.join("、", recent)).append('\n');
        }
        prompt.append("只回复一个版式标识。");
        return prompt.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
