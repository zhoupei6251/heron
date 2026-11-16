package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.bo.ResearchTaskCreateBo;
import org.dromara.heron.domain.bo.ResearchTaskQueryBo;
import org.dromara.heron.domain.vo.ResearchTaskVo;
import org.dromara.heron.service.IResearchTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 研究任务：发起、查看、取消。
 *
 * <p>发起是异步的：一轮研究最长 10 分钟，同步等必然被网关掐断。接口立刻返回当前状态，
 * 结果靠列表/详情回看。
 *
 * <p>条件装配跟服务保持一致：{@code heron.agent.enabled=false} 时这个控制器整个不注册。
 * 否则服务 Bean 不存在，控制器装配失败会把整个应用带崩。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/research")
@ConditionalOnProperty(prefix = "heron.agent", name = "enabled", havingValue = "true")
public class ResearchTaskController extends BaseController {

    private static final String DEFAULT_GOAL =
        "判断这条机会值不值得做成一条 60 秒的数据视频，并说明你的依据。";

    private final IResearchTaskService researchTasks;

    @SaCheckPermission("heron:research:list")
    @GetMapping("/list")
    public R<PageResult<ResearchTaskVo>> list(ResearchTaskQueryBo query, PageQuery pageQuery) {
        return R.ok(researchTasks.queryPageList(query, pageQuery));
    }

    @SaCheckPermission("heron:research:query")
    @GetMapping("/{researchId}")
    public R<ResearchTaskVo> getInfo(@PathVariable long researchId) {
        return R.ok(toVo(researchTasks.find(researchId)));
    }

    /** 为一条机会建研究任务并立刻排上执行；同一机会同一天复用已有任务。 */
    @SaCheckPermission("heron:research:run")
    @RepeatSubmit
    @Log(title = "数据机会研究", businessType = BusinessType.INSERT, isSaveRequestData = false)
    @PostMapping
    public R<ResearchTaskVo> create(@Valid @RequestBody ResearchTaskCreateBo body) {
        String goal = body.getGoal() == null || body.getGoal().isBlank()
            ? DEFAULT_GOAL : body.getGoal().trim();
        long researchId = researchTasks.createFor(body.getOpportunityId(), goal);
        // 走 requeue 而不是 trigger：同一天再点一次时拿到的是已结束的旧任务，
        // 而 trigger 只领得到的排队中或租约过期的行——直接调它按钮就变成静默无反应
        return R.ok(toVo(researchTasks.requeue(researchId)));
    }

    /** 重跑：只有排队中或租约已过期的任务会被重新领走。 */
    @SaCheckPermission("heron:research:run")
    @RepeatSubmit
    @Log(title = "数据机会研究", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PostMapping("/{researchId}/run")
    public R<ResearchTaskVo> run(@PathVariable long researchId) {
        return R.ok(toVo(researchTasks.trigger(researchId)));
    }

    @SaCheckPermission("heron:research:cancel")
    @Log(title = "数据机会研究", businessType = BusinessType.UPDATE)
    @PostMapping("/{researchId}/cancel")
    public R<ResearchTaskVo> cancel(@PathVariable long researchId) {
        return R.ok(toVo(researchTasks.cancel(researchId)));
    }

    /** 重跑已结束的任务：先回退到排队再触发，直接触发是领不走的。 */
    @SaCheckPermission("heron:research:run")
    @RepeatSubmit
    @Log(title = "数据机会研究", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PostMapping("/{researchId}/rerun")
    public R<ResearchTaskVo> rerun(@PathVariable long researchId) {
        return R.ok(toVo(researchTasks.requeue(researchId)));
    }

    private static ResearchTaskVo toVo(HeronResearchTask task) {
        return task == null ? null : MapstructUtils.convert(task, ResearchTaskVo.class);
    }
}
