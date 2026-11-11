package org.dromara.heron.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.heron.service.AutoPauseStore;
import org.springframework.stereotype.Component;

/** 暂停标记放 Redis：重启不丢，且不需要新增配置表。 */
@Component
@Slf4j
public class RedisAutoPauseStore implements AutoPauseStore {
    private static final String PAUSED_KEY = "heron:news:auto:paused";

    /** 读到之前的进程内缓存；Redis 不可用时至少让当前进程的暂停立即生效。 */
    private volatile Boolean cached;

    @Override
    public boolean paused() {
        Boolean now = cached;
        if (now != null) {
            return now;
        }
        boolean value = false;
        try {
            value = Boolean.TRUE.equals(RedisUtils.getCacheObject(PAUSED_KEY));
        } catch (RuntimeException e) {
            log.warn("读取自动产出暂停标记失败，按运行中处理: {}", e.getMessage());
        }
        cached = value;
        return value;
    }

    @Override
    public void pause(boolean paused) {
        cached = paused;
        try {
            RedisUtils.setCacheObject(PAUSED_KEY, paused);
        } catch (RuntimeException e) {
            // 当前进程立即生效；重启后会回到运行中，日志里说清楚
            log.warn("暂停标记未能写进 Redis，重启后会恢复运行: {}", e.getMessage());
        }
    }
}
