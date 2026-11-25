package org.dromara.heron.service;

/**
 * 自动产出暂停标记的存储：生产走 Redis（重启不丢），测试可换成内存实现。
 *
 * <p>暂停有两种来法：人按下的，和连续失败后自动刹车的。两者只在布尔位上是同一件事，
 * 「为什么停了」必须一起存，否则暂停原因埋在一次运行时的日志里，看板只能看到一个没有解释的暂停。
 */
public interface AutoPauseStore {
    boolean paused();

    void pause(boolean paused);

    /** 带原因的暂停：自动刹车的入口，原因要能被健康概览读出来。 */
    default void pause(boolean paused, String reason) {
        pause(paused);
    }

    /**
     * 暂停原因；没人暂停或原因是人工操作时可以为 null。
     *
     * <p>默认实现不保存原因——内存实现只需要布尔位，就不该假装记得原因。
     */
    default String pauseReason() {
        return null;
    }
}
