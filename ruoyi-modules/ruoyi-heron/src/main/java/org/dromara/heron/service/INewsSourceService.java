package org.dromara.heron.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.heron.domain.bo.NewsSourceBo;
import org.dromara.heron.domain.bo.NewsSourceQuery;
import org.dromara.heron.domain.vo.NewsNowPlatformVo;
import org.dromara.heron.domain.vo.NewsSourceVo;

import java.util.List;

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
    /** NewsNow 平台目录，附带该平台是否已建来源。 */
    List<NewsNowPlatformVo> newsnowPlatforms();
    /** 批量把平台目录里的平台建成 newsnow 来源，已存在的跳过；返回新建条数。 */
    int enableNewsnow(List<String> platformIds);
}
