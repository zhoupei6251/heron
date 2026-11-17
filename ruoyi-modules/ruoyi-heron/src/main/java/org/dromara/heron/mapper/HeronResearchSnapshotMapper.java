package org.dromara.heron.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.heron.domain.HeronResearchSnapshot;
import org.dromara.heron.domain.vo.ResearchSnapshotVo;

/** 研究 attempt 的数据快照：唯一键是（研究, attempt, 源）。 */
public interface HeronResearchSnapshotMapper
    extends BaseMapperPlus<HeronResearchSnapshot, ResearchSnapshotVo> {
}
