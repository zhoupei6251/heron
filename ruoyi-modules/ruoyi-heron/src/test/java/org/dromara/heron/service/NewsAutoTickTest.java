package org.dromara.heron.service;

import org.dromara.heron.config.HeronNewsProperties;
import org.dromara.heron.mapper.*;
import org.dromara.heron.service.impl.NewsAutoService;
import org.dromara.heron.service.impl.NewsCandidateScreener;import org.dromara.heron.support.NewsMysqlTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 自动产出槽位：重复触发只算一次，提前退出必须留下可查的原因。 */
class NewsAutoTickTest extends NewsMysqlTestSupport {
    @Autowired private HeronNewsSourceMapper sourceMapper;
    @Autowired private HeronNewsCandidateMapper candidateMapper;
    @Autowired private HeronNewsWorkMapper workMapper;
    @Autowired private HeronVideoVersionMapper versionMapper;
    @Autowired private HeronAutoSlotMapper slotMapper;

    @Test
    void sameSlotFiresOnceAndLeavesReason() {
        NewsAutoService auto = autoService();

        auto.tick("0 0 11 * * ?");
        auto.tick("0 0 11 * * ?");

        assertEquals(1, slotCount(), "同一槽位重复触发不能产生第二行");
        assertEquals("skipped", jdbc.queryForObject("SELECT status FROM heron_auto_slot", String.class));
        assertEquals("no_candidate", jdbc.queryForObject("SELECT detail FROM heron_auto_slot", String.class));
    }

    @Test
    void disabledAndPausedBothLeaveTrace() {
        NewsAutoService auto = autoService();
        HeronNewsProperties off = new HeronNewsProperties();
        off.setAutoEnabled(false);
        newAuto(off).tick("0 0 15 * * ?");
        assertEquals("auto_disabled",
            jdbc.queryForObject("SELECT detail FROM heron_auto_slot WHERE slot_time = ?", String.class, "0 0 15 * * ?"));

        auto.pause(true);
        try {
            auto.tick("0 0 19 * * ?");
            assertEquals("paused",
                jdbc.queryForObject("SELECT detail FROM heron_auto_slot WHERE slot_time = ?", String.class, "0 0 19 * * ?"));
        } finally {
            auto.pause(false);
        }
    }

    private NewsAutoService autoService() {
        HeronNewsProperties properties = new HeronNewsProperties();
        properties.setAutoEnabled(true);
        properties.setAutoConfirmEnabled(false);
        properties.setAutoActorId(OWNER);
        return newAuto(properties);
    }

    /** 测试里没有模型：provider 返回 null，筛选层直接放弃，走热度排序。 */
    @SuppressWarnings("unchecked")
    private static NewsCandidateScreener noModelScreener() {
        return new NewsCandidateScreener(
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class), null);
    }

    private NewsAutoService newAuto(HeronNewsProperties properties) {
        return new NewsAutoService(properties, sourceMapper, candidateMapper, workMapper, versionMapper, slotMapper,
            maintenance, memoryPause(), noModelScreener(),
            org.mockito.Mockito.mock(org.dromara.heron.service.impl.EditorialDecisionService.class),
            org.mockito.Mockito.mock(org.dromara.heron.service.impl.NewsProductionDomain.class),
            org.mockito.Mockito.mock(HeronDataSourceMapper.class),
            org.mockito.Mockito.mock(HeronDatasetUsageMapper.class),
            clock);
    }

    /** 测试里不接 Redis：暂停标记用进程内实现即可。 */
    private AutoPauseStore memoryPause() {
        return new AutoPauseStore() {
            private boolean paused;

            @Override
            public boolean paused() {
                return paused;
            }

            @Override
            public void pause(boolean value) {
                paused = value;
            }
        };
    }

    private int slotCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM heron_auto_slot", Integer.class);
    }
}
