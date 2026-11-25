package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.service.INewsMetricService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 选排版：一次轻量模型调用 + 系统硬约束。
 * 约束比模型更可靠——最近两期用过的模板一律排除，模型失败或答非所问就退回「最久未用」。
 *
 * <p>表现回流也落在这一层：两套选择入口（线内编辑与人工认领）最后都到这里选版式，
 * 样本足够时按历史表现带权抽样，只影响概率、不影响契约，也不拉黑任何一套模板。
 */
@Slf4j
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
    /** 表现来源；不配置时为中性，选模板与没有这份偏好时完全一致。 */
    private final ObjectProvider<INewsMetricService> metricsProvider;

    /** 单测与无表现来源的构造：按中性偏好走原有路径。 */
    NewsTemplatePicker(NewsProductionDomain domain, ObjectProvider<OpenAiModelClient> modelProvider,
                       HeronNewsWorkMapper workMapper) {
        this(domain, modelProvider, workMapper, null);
    }

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
        // 有证据就先按证据抽：跑得好的那套下一期该被多抽到几次。
        // 证据不足时 preferredPick 返回 null，下面的模型路径与不带偏好时逐字相同。
        String preferred = preferredPick(candidates);
        if (preferred != null) {
            return preferred;
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

    /**
     * 按表现偏好抽一套版式。
     *
     * <p>只做加权，不做一票否决：权重下限 0.5，表现差的模板只被少抽到，不会永久拉黑——
     * 数据会变，选题也会变。证据不到 L4、样本不稳、取不到表现，或候选里一套有信号的都没有时
     * 返回 null，交给下面的模型路径，选择结果与不带偏好完全一致。
     *
     * <p>有证据时不再问模型：模型挑的是「标题适配度」，样本给的是「这类画面真实跑出来的结果」，
     * 两者都指向同一件事时用证据更硬的那份；证据不足时仍由模型决定。
     */
    private String preferredPick(List<NewsProductionDomain.TemplateInfo> candidates) {
        MetricInsightAnalyzer.Preference preference = templatePreference();
        if (preference == null || !preference.hasSignal()) {
            return null;
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        boolean signal = false;
        for (NewsProductionDomain.TemplateInfo item : candidates) {
            double weight = preference.weightOf(item.key());
            weights.put(item.key(), weight);
            signal = signal || weight != 1d;
        }
        if (!signal) {
            return null;
        }
        String picked = weightedPick(new ArrayList<>(weights.keySet()), weights, ThreadLocalRandom.current());
        if (picked == null) {
            return null;
        }
        log.info("按表现偏好选版式：{}（证据等级 {}，样本 {} 条，权重 {}）",
            picked, preference.tier(), preference.samples(), weights.get(picked));
        return picked;
    }

    /**
     * 带权抽样：权重只改变概率，不改候选集合。
     *
     * <p>随机源由调用方传入，单测才能用固定种子跑出可复现的分布。
     * 全部权重都不为正时返回 null——没有可抽的东西，不猜。
     */
    static String weightedPick(List<String> candidates, Map<String, Double> weights, Random random) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        double total = 0d;
        for (String key : candidates) {
            double weight = weights.getOrDefault(key, 1d);
            if (weight > 0d) {
                total += weight;
            }
        }
        if (total <= 0d) {
            return null;
        }
        double point = random.nextDouble() * total;
        for (String key : candidates) {
            double weight = weights.getOrDefault(key, 1d);
            if (weight <= 0d) {
                continue;
            }
            point -= weight;
            if (point < 0d) {
                return key;
            }
        }
        // 浮点误差兜底：落到最后一个有权重的候选，不返回 null。
        for (int index = candidates.size() - 1; index >= 0; index--) {
            if (weights.getOrDefault(candidates.get(index), 1d) > 0d) {
                return candidates.get(index);
            }
        }
        return null;
    }

    /** 只按模板维度读偏好：表现数字全部来自人工回填的采样行，不引入第二套口径。 */
    private MetricInsightAnalyzer.Preference templatePreference() {
        if (metricsProvider == null) {
            return null;
        }
        try {
            INewsMetricService metrics = metricsProvider.getIfAvailable();
            return metrics == null ? null
                : MetricInsightAnalyzer.preferenceOf(metrics.performanceInsight("template"));
        } catch (RuntimeException e) {
            // 读不到表现就按中性处理：选版式不能因为归因查询失败而停摆，但原因要留痕。
            log.warn("读表现偏好失败，本次按中性处理: {}", e.getMessage());
            return null;
        }
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

    /**
     * 最近用过的模板，最新在前。
     *
     * <p>只认当前目录里还在的模板：历史作品会记着已删除的模板键，它们既进不了候选，
     * 却会占掉「最近 2 期排除」的名额，让真正刚用过的在册模板逃过排除，还会被写进给模型的清单里。
     */
    private List<String> recentKeys() {
        List<HeronNewsWork> works = workMapper.selectList(QueryBuilder.lambda(HeronNewsWork.class)
            .orderByDesc(HeronNewsWork::getCreateTime, HeronNewsWork::getId).last("LIMIT " + SCAN_LIMIT).build());
        return recentInstalled(works, domain.availableTemplates());
    }

    /**
     * 从按时间倒序的作品里取最近用过的在册模板键。
     *
     * <p>已删模板的键和还没选版式的作品（templateKey 为空）都不占名额。
     */
    static List<String> recentInstalled(List<HeronNewsWork> works, List<String> installed) {
        Set<String> known = Set.copyOf(installed);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (HeronNewsWork work : works) {
            String key = work.getTemplateKey();
            if (key != null && known.contains(key)) {
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
