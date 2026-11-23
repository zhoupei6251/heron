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
    /**
     * 每个模板带着自己的视觉风格（说明里写清适用场景），所以选模板就是选风格：
     * 同一期不该出现两套皮，也不该随机换皮。
     */
    private static final String SYSTEM =
        "你是新闻短视频频道的排版与视觉风格编辑。根据标题与摘要，从给出的版式里选一个最合适的；"
            + "版式说明里写了这套画面的视觉风格与适用场景，按它选。"
            + "题材有配图、引语或排序时，优先选画面支持它的版式。只回复一个版式标识，不要解释。";

    private final NewsProductionDomain domain;
    private final ObjectProvider<OpenAiModelClient> modelProvider;
    private final HeronNewsWorkMapper workMapper;

    /**
     * 按生产线收窄候选。判断依据是模板自己声明的 supportedLines，不是命名前缀——
     * 改模板名不该改出片逻辑。没写 supportedLines 的老模板才退回前缀推断。
     *
     * <p>不提供「不传线路」的重载：那会让两条线的模板都进候选，
     * 于是一条文章候选可能选中数据看板模板，脚本却按解说线写 text 场景，
     * 契约校验当场炸。线路是调用方必须交代清楚的事。
     */
    public String pick(String title, String summary, String line) {
        return pick(title, summary, line, null);
    }

    /**
     * 带叙事结论的选模板。
     *
     * <p>叙事先把风格定死：同一种叙事每次都该长成同一套画面，
     * 所以模型只在风格内部挑实现，不再在几套画面之间现挑。
     * 收窄到只剩一套时连模型都不用调——风格是机器定死的，请模型再挑一次只会让它随机。
     */
    public String pick(String title, String summary, String line, String narrative) {
        return pick(title, summary, line, narrative, null);
    }

    /**
     * 叙事 + 视觉模式一起收窄。
     *
     * <p>3D 不是又一套风格，而是同一套风格下的另一种实现：先按叙事定风格，再按模式定实现。
     * 要求 3D 却没有 3D 模板时返回 null（拒绝这条候选），不退回 2D——
     * geo 数据进了 2D 模板画不出图，退回只会把失败推迟到渲染。
     */
    public String pick(String title, String summary, String line, String narrative, VisualMode mode) {
        List<NewsProductionDomain.TemplateInfo> catalog = domain.templateCatalog();
        List<String> problems = StoryStyleMap.violations(catalog);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("模板目录与叙事契约对不上：" + String.join("；", problems));
        }
        return pickFrom(catalog.stream().filter(item -> item.supports(line)).toList(),
            title, summary, line, narrative, mode);
    }

    String pickFrom(List<NewsProductionDomain.TemplateInfo> all, String title, String summary, String line,
                    String narrative, VisualMode mode) {
        if (all.isEmpty()) {
            return null;
        }
        // 叙事先定风格。收窄不到说明模板产物没登记这个叙事，
        // 退回全量让模型挑——配置缺了不该把这条线卡死，但也不能装作收窄成功。
        List<NewsProductionDomain.TemplateInfo> narrowed = StoryStyleMap.narrow(all, narrative);
        List<NewsProductionDomain.TemplateInfo> pool = narrowed.isEmpty() ? all : narrowed;
        if (mode != null) {
            pool = pool.stream().filter(item -> item.visualMode() == mode).toList();
            if (pool.isEmpty()) {
                return null;
            }
        }
        if (pool.size() == 1) {
            return pool.get(0).key();
        }
        List<String> recent = recentKeys();
        List<String> blocked = recent.stream().limit(BLOCK_LIMIT).toList();
        List<NewsProductionDomain.TemplateInfo> candidates = pool.stream()
            .filter(item -> !blocked.contains(item.key())).toList();
        if (candidates.isEmpty()) {
            // 退回收窄后的池，不退回全量：叙事已经把风格定死了，
            // 退回全量等于让风格随机轮换，正是这套规则要杜绝的。
            candidates = pool;
        }
        String reply = null;
        OpenAiModelClient model = modelProvider.getIfAvailable();
        if (model != null) {
            try {
                reply = model.chatWithoutUsage(SYSTEM, prompt(title, summary, candidates, recent, line, narrative, mode));
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
                                 List<String> recent, String line, String narrative, VisualMode mode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("本期标题：").append(nullToEmpty(title)).append('\n');
        prompt.append("本期摘要：").append(nullToEmpty(summary)).append('\n');
        if (mode == VisualMode.THREE_D) {
            prompt.append("本期视觉模式：3D（空间数据，尽量用三维版式）\n");
        }
        if (narrative != null && !narrative.isBlank()) {
            // 风格已经定死，模型挑的是同一套画面里的哪个实现，不是换一套画面
            prompt.append("本期叙事类型：").append(narrative)
                .append("（画面风格已按叙事定好，下面几套是同一风格的不同版式，按标题适配度挑）\n");
        }
        prompt.append("可用版式（标识 名称：说明；画面支持哪些场景类型写在其后）：\n");
        for (NewsProductionDomain.TemplateInfo item : candidates) {
            prompt.append("- ").append(item.key()).append(' ').append(item.name())
                .append('：').append(item.description());
            // 画面能力进选版式：有原文配图的题材选不了图片版式，出片后只能退化成纯文字
            if (item.supportedSceneTypes() != null && !item.supportedSceneTypes().isEmpty()) {
                prompt.append("（画面支持：").append(String.join("、", item.supportedSceneTypes())).append('）');
            }
            if (narrative != null && !narrative.isBlank() && item.style() != null
                && item.style().label() != null && !item.style().label().isBlank()) {
                prompt.append("（风格：").append(item.style().label()).append('）');
            }
            prompt.append('\n');
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
