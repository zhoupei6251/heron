package org.dromara.heron.service;

/** 自动产出暂停标记的存储：生产走 Redis（重启不丢），测试可换成内存实现。 */
public interface AutoPauseStore {
    boolean paused();

    void pause(boolean paused);
}
