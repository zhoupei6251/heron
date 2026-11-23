package org.dromara.heron.service.impl;

import org.dromara.heron.service.impl.NewsProductionDomain.TemplateInfo;
import org.dromara.heron.service.impl.NewsProductionDomain.TemplateStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 叙事类型 → 视觉风格。
 *
 * <p>映射登记在模板产物里：每套风格在 {@code data/templates/_shared/styles.json} 写下自己能承载哪些叙事，
 * 构建时写进模板的 {@code template.json}，运行时从这里读。登记的是风格而不是模板——
 * 一套画面语法能承载多种叙事，模板只是这套语法的实现。
 *
 * <p>为什么要机器定：同一种叙事每次都该长成同一套画面。风格换来换去，这条栏目就没有视觉身份了；
 * 而让模型每次现挑，挑的还是「哪套看着顺眼」，不是「这条片子讲的是什么」。先有故事，才知道该用哪套画面。
 *
 * <p>两条硬规则由 {@link #violations} 查：一种叙事只能对应一套画面（不然收窄收不窄），
 * 六种叙事都得有画面承接（不然这类故事出不了片）。
 */
final class StoryStyleMap {

    private StoryStyleMap() {
    }

    /**
     * 按叙事收窄候选。
     *
     * <p>契约不认的叙事不参与收窄：宁可退回全量让模型挑，
     * 也不要拿一个没定义过的类型去筛，筛空了还不知道是筛错了还是没登记。
     */
    static List<TemplateInfo> narrow(List<TemplateInfo> candidates, String narrative) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (narrative == null || narrative.isBlank() || !DatasetStoryContract.NARRATIVES.contains(narrative)) {
            return List.copyOf(candidates);
        }
        return candidates.stream().filter(item -> narrates(item, narrative)).toList();
    }

    /**
     * 模板目录与叙事契约是否还对得上。
     *
     * <p>三件事：不认的叙事类型（登记写错了）、没人承接的叙事（这类故事出不了片）、
     * 一个叙事落到两套风格上（收窄收不窄，等于没定）。
     */
    static List<String> violations(List<TemplateInfo> catalog) {
        List<String> problems = new ArrayList<>();
        // 叙事 → 承接它的风格。多套模板共用一套风格是常态（同一语法的不同版式），
        // 按风格判重，不能按模板判重。
        Map<String, String> owner = new LinkedHashMap<>();
        if (catalog != null) {
            for (TemplateInfo item : catalog) {
                TemplateStyle style = item.style();
                if (style == null || style.narratives() == null) {
                    continue;
                }
                for (String narrative : style.narratives()) {
                    if (!DatasetStoryContract.NARRATIVES.contains(narrative)) {
                        problems.add("模板 " + item.key() + " 的风格登记了契约不认的叙事类型 " + narrative);
                        continue;
                    }
                    String previous = owner.put(narrative, style.key());
                    if (previous != null && !previous.equals(style.key())) {
                        problems.add("叙事类型 " + narrative + " 同时登记在风格 " + previous + " 与 " + style.key()
                            + " 上——一种叙事只能对应一套画面");
                    }
                }
            }
        }
        for (String narrative : DatasetStoryContract.NARRATIVES) {
            if (!owner.containsKey(narrative)) {
                problems.add("叙事类型 " + narrative + " 没有任何风格承接——这类故事选不出画面");
            }
        }
        return problems;
    }

    private static boolean narrates(TemplateInfo item, String narrative) {
        TemplateStyle style = item.style();
        return style != null && style.narratives() != null && style.narratives().contains(narrative);
    }
}
