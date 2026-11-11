package org.dromara.heron.service;

import org.dromara.heron.domain.vo.HeronHealthVo;

/** 只读健康概览，供操作台底部状态条使用。 */
public interface IHeronHealthService {
    HeronHealthVo overview();
}
