package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.vo.ResearchTaskVo;

/** 研究任务：唯一键是 dedup_key（机会 + 日期），同日重跑走 attempt_no 而不是新建行。 */
public interface HeronResearchTaskMapper extends BaseMapperPlus<HeronResearchTask, ResearchTaskVo> {
}
