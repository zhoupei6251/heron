package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.HeronContentDecision;
import org.dromara.heron.domain.bo.ContentDecisionQueryBo;
import org.dromara.heron.domain.vo.ContentDecisionVo;

/**
 * 内容决策：把研究结论接进业务，并接受人的裁决。
 */
public interface IContentDecisionService {

    /**
     * 从一份研究报告落一条决策。
     *
     * <p>幂等：同一研究的同一次执行只落一条。重跑是新的 attempt，会落新的一条，
     * 历史决策不被覆盖——决策一旦产生就是历史事实。
     *
     * @return 落了返回 id；报告里没有结论时返回 null（没判断就没有决策）
     */
    Long recordFromResearch(long researchId, int attemptNo);

    /** 分页查询。 */
    PageResult<ContentDecisionVo> queryPageList(ContentDecisionQueryBo bo, PageQuery pageQuery);

    ContentDecisionVo queryById(long decisionId);

    /** 按 id 取实体，找不到返回 null（核验要用它判断决策类型）。 */
    HeronContentDecision findDecisionById(long decisionId);

    /**
     * 人工作出裁决。
     *
     * <p>只接受待处理状态：决策一旦裁决就是历史事实，后来的点击不该改写它。
     *
     * @param accepted true 采纳，false 否决
     * @param note     理由；否决时必填——说不出为什么否决的否决，等于没记
     */
    ContentDecisionVo decide(long decisionId, boolean accepted, String note);

    /**
     * 采纳并提升为候选：两步在同一个事务里。
     *
     * <p>分开调会出现半截状态——采纳写进去了、候选没建出来，用户看到报错，
     * 再点一次却被告知「已经裁决过」，两边都不对。
     *
     * @param note                 采纳意见，可以为空
     * @param verificationOverride 核验判 DISAGREE 时人工放行的理由；其余情况可为空
     * @param evidenceOverride     证据链不完整时人工放行的理由；其余情况可为空
     * @return 候选 id
     */
    long acceptAndPromote(long decisionId, String note, String verificationOverride, String evidenceOverride);

    /**
     * 把核验员的判定挂到决策上。
     *
     * <p>分开存而不是覆盖 decisionType：研究员判 PRODUCE、核验员判 DISAGREE，
     * 这两个事实都要留给看的人，合成一个值就再也看不出冲突了。
     */
    void attachVerification(long decisionId, String verdict, String reason, String conflictsJson);

    /**
     * 重复选题检测：同一机会是否已经有过待处理或已采纳的决策。
     *
     * @return 已存在的那条决策；没有则返回 null
     */
    HeronContentDecision findExistingDecision(long opportunityId);
}
