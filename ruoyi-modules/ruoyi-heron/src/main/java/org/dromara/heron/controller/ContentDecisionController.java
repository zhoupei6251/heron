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
import org.dromara.heron.service.IContentDecisionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** 裁决意见。采纳时可省略，否决时必填。 */
    public record DecisionNoteBo(String note) {
    }
}
