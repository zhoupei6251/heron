package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.vo.ResearchTaskVo;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 续命一拍就三件事：还在跑就续租约、看到取消就置位、已结束就不动。
 * 这三件事各自对应一种真实事故：长任务被误回收、取消不生效、给结束的任务续命。
 */
@DisplayName("租约续命：续租、取消探测与终止态")
class ResearchLeaseKeeperTest {

    private static final long RESEARCH_ID = 9L;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneId.of("UTC"));

    /** 真实 wrapper 要解析 lambda 到列名，单测里得先有表元数据。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "researchTask"), HeronResearchTask.class);
    }

    private static HeronResearchTask row(String status) {
        HeronResearchTask task = new HeronResearchTask();
        task.setResearchId(RESEARCH_ID);
        task.setStatus(status);
        return task;
    }

    /** 不该写库的用例：一调 lambda 就炸，省得靠 verify 才发现在续命。 */
    private static HeronResearchTaskMapper readOnlyMapper(HeronResearchTask row) {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(RESEARCH_ID)).thenReturn(row);
        when(mapper.lambda()).thenThrow(new AssertionError("这一拍不该写库"));
        return mapper;
    }

    @Test
    @DisplayName("执行中：心跳与租约都推到「现在 + 租约长度」")
    void renewsWhileRunning() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(RESEARCH_ID)).thenReturn(row(HeronResearchTask.STATUS_RUNNING));
        when(mapper.update(any(Wrapper.class))).thenReturn(1);
        List<LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>> chains = new ArrayList<>();
        when(mapper.lambda()).thenAnswer(invocation -> {
            var chain = new LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>(mapper);
            chains.add(chain);
            return chain;
        });

        new ResearchLeaseKeeper(mapper, RESEARCH_ID, CLOCK, LEASE).tick();

        assertEquals(1, chains.size(), "执行中的任务每拍都要续命");
        var values = chains.get(0).getParamNameValuePairs().values();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        assertTrue(values.contains(now.plus(LEASE)), "租约没续到 现在+5 分钟：" + values);
        assertTrue(values.contains(now), "心跳时间没更新：" + values);
    }

    @Test
    @DisplayName("库里已取消：置位取消信号，不再续命")
    void detectsCancellation() {
        ResearchLeaseKeeper keeper =
            new ResearchLeaseKeeper(readOnlyMapper(row(HeronResearchTask.STATUS_CANCELLED)),
                RESEARCH_ID, CLOCK, LEASE);

        keeper.tick();

        assertTrue(keeper.cancelled(), "取消没被探测到，研究就会继续烧钱");
    }

    @Test
    @DisplayName("已结束：既不续命也不置位")
    void ignoresFinishedRows() {
        ResearchLeaseKeeper keeper =
            new ResearchLeaseKeeper(readOnlyMapper(row(HeronResearchTask.STATUS_SUCCEEDED)),
                RESEARCH_ID, CLOCK, LEASE);

        keeper.tick();

        assertFalse(keeper.cancelled());
    }

    @Test
    @DisplayName("任务行没了：安静返回，不抛给研究线程")
    void toleratesMissingRow() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(RESEARCH_ID)).thenReturn(null);
        ResearchLeaseKeeper keeper = new ResearchLeaseKeeper(mapper, RESEARCH_ID, CLOCK, LEASE);

        assertDoesNotThrow(keeper::tick);
        assertFalse(keeper.cancelled());
    }
}
