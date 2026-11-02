package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.heron.domain.bo.NewsSourceBo;
import org.dromara.heron.domain.bo.NewsSourceQuery;
import org.dromara.heron.domain.vo.NewsSourceVo;

/** 新闻来源维护与采集。 */
public interface INewsSourceService {
    PageResult<NewsSourceVo> list(NewsSourceQuery query);
    NewsSourceVo query(long sourceId);
    void add(NewsSourceBo bo);
    void edit(NewsSourceBo bo);
    void remove(long sourceId);
    /** 立即采集一个来源；失败原因先如实入库，再把异常原样抛给调用方。 */
    void fetch(long sourceId);
    /** 调度入口：一轮最多采集两个到期来源，返回实际领取数。 */
    int collectDue();
}
