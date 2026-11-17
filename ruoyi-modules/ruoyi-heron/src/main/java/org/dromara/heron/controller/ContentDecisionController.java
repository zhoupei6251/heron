package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.bo.ContentDecisionQueryBo;
import org.dromara.heron.domain.vo.ContentDecisionVo;
import org.dromara.heron.domain.vo.ResearchCostVo;
import org.dromara.heron.service.IContentDecisionService;
import org.dromara.heron.service.impl.ResearchCostService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容决策中心：看 Agent 给的判断，并作出人的裁决。
 *
 * <p>「采纳」不等于允许生产——plan.md §7.1 说得明白，最终是否生产仍由业务规则校验。
 * 这里只负责记下人的意图，生产闸在它自己的链路上。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/decision")
public class ContentDecisionController extends BaseController {

    private final IContentDecisionService decisions;
    private final ResearchCostService costs;

    @SaCheckPermission("heron:decision:list")
    @GetMapping("/list")
    public R<PageResult<ContentDecisionVo>> list(ContentDecisionQueryBo query, PageQuery pageQuery) {
        return R.ok(decisions.queryPageList(query, pageQuery));
    }

    @SaCheckPermission("heron:decision:query")
    @GetMapping("/{decisionId}")
    public R<ContentDecisionVo> getInfo(@PathVariable long decisionId) {
        return R.ok(decisions.queryById(decisionId));
    }

    /** 采纳 Agent 的判断。 */
    @SaCheckPermission("heron:decision:decide")
    @Log(title = "内容决策", businessType = BusinessType.UPDATE)
    @PostMapping("/{decisionId}/accept")
    public R<ContentDecisionVo> accept(@PathVariable long decisionId,
                                       @RequestBody(required = false) DecisionNoteBo body) {
        return R.ok(decisions.decide(decisionId, true, body == null ? null : body.note()));
    }

    /** 人工否决：理由必填，说不出理由的否决事后没法复盘，也没法反馈给模型。 */
    @SaCheckPermission("heron:decision:decide")
    @Log(title = "内容决策", businessType = BusinessType.UPDATE)
    @PostMapping("/{decisionId}/reject")
    public R<ContentDecisionVo> reject(@PathVariable long decisionId,
                                       @RequestBody(required = false) DecisionNoteBo body) {
        return R.ok(decisions.decide(decisionId, false, body == null ? null : body.note()));
    }

    /**
     * 采纳并提升为选题候选。
     *
     * <p>两个动作合成一个接口是刻意的：采纳之后不做提升，那条判断就停在决策中心里没人用。
     * 分成两步的话，第二步一定会被忘掉。后端也把两步放进同一个事务，不留半截状态。
     *
     * <p>提升只产出候选，不建生产任务——plan.md §16 要求它「作为候选之一，不取代总编辑」。
     */
    @SaCheckPermission("heron:decision:decide")
    @Log(title = "内容决策", businessType = BusinessType.UPDATE)
    @PostMapping("/{decisionId}/promote")
    public R<PromotionVo> promote(@PathVariable long decisionId,
                                  @RequestBody(required = false) DecisionNoteBo body) {
        long candidateId = decisions.acceptAndPromote(decisionId,
            body == null ? null : body.note(),
            body == null ? null : body.verificationOverride(),
            body == null ? null : body.evidenceOverride());
        return R.ok(new PromotionVo(decisionId, candidateId));
    }

    /** 成本与质量概览：监督工作台用。 */
    @SaCheckPermission("heron:decision:list")
    @GetMapping("/cost")
    public R<ResearchCostVo> cost(@RequestParam(required = false) Integer days) {
        return R.ok(costs.overview(days == null ? 30 : days));
    }

    /**
     * 裁决意见。
     *
     * @param note                 采纳意见，采纳时可省略、否决时必填
     * @param verificationOverride 核验判 DISAGREE 时人工放行的理由；只在提升时用
     * @param evidenceOverride     证据链不完整时人工放行的理由；只在提升时用
     */
    public record DecisionNoteBo(String note, String verificationOverride, String evidenceOverride) {
    }
    /** 提升结果：决策 id 与产出的候选 id。 */
    public record PromotionVo(long decisionId, long candidateId) {
    }
}
