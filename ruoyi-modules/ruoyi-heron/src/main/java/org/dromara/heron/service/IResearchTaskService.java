package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.bo.ResearchTaskQueryBo;
import org.dromara.heron.domain.vo.ResearchTaskVo;

/**
 * 研究任务的创建与执行。一次任务对应一次 Agent 会话。
 */
public interface IResearchTaskService {

    /**
     * 为一条机会创建研究任务；同一机会同一天已存在则直接返回既有任务（不重复建）。
     *
     * @return 任务 id
     */
    long createFor(long opportunityId, String goal);

    /**
     * 同步执行一次：领租约、跑会话、落结论。已结束或正在跑的任务不会被重复执行。
     *
     * @return 执行后的任务状态
     */
    String run(long researchId);

    /** 按 id 取任务，找不到返回 null。 */
    HeronResearchTask find(long researchId);

    /**
     * 异步触发：立刻返回当前状态，执行在后台线程。
     *
     * <p>同步触发会让 HTTP 请求挂满整轮研究（最长 10 分钟），必然被网关掐断。
     */
    HeronResearchTask trigger(long researchId);

    /**
     * 重跑：把已结束的任务重新排上队再触发。
     *
     * <p>为什么不能直接调 {@link #trigger}：领取条件只认排队中或租约过期的任务，
     * 已结束的行永远领不走——按钮点下去什么都不会发生。重排是一次显式的状态回退。
     */
    HeronResearchTask requeue(long researchId);

    /**
     * 取消：排队中的直接置为已取消；执行中的由租约续命线程探测到后收尾。
     *
     * @return 取消后的任务
     */
    HeronResearchTask cancel(long researchId);

    /** 分页查询。 */
    PageResult<ResearchTaskVo> queryPageList(ResearchTaskQueryBo bo, PageQuery pageQuery);
}
