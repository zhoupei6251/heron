package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.domain.vo.ResearchTaskVo;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.impl.shared.research.ResearchLeaseKeeper;
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
 * 续命一拍就四件事：还在跑且令牌是自己的就续租约、看到取消就置位、已结束就不动、
 * 令牌不是自己的就丢权。
 *
 * <p>丢权这一条是后补的：没有它，被回收的旧执行会继续给新 attempt 续租约，
 * 新执行真挂了也永不过期，任务卡死在 running。
 */
@DisplayName("租约续命：续租、取消探测、丢权与终止态")
class ResearchLeaseKeeperTest {

    private static final long RESEARCH_ID = 9L;
    private static final int ATTEMPT = 3;
    private static final String TOKEN = "token-3";
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
        task.setId(RESEARCH_ID);
        task.setStatus(status);
        task.setAttemptNo(ATTEMPT);
        task.setRunToken(TOKEN);
        return task;
    }

    private static ResearchLeaseKeeper keeper(HeronResearchTaskMapper mapper) {
        return new ResearchLeaseKeeper(mapper, RESEARCH_ID, ATTEMPT, TOKEN, CLOCK, LEASE);
    }

    /** 不该写库的用例：一调 lambda 就炸，省得靠 verify 才发现在续命。 */
    private static HeronResearchTaskMapper readOnlyMapper(HeronResearchTask row) {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(RESEARCH_ID)).thenReturn(row);
        when(mapper.lambda()).thenThrow(new AssertionError("这一拍不该写库"));
        return mapper;
    }

    @Test
    @DisplayName("执行中：心跳与租约都推到「现在 + 租约长度」，且条件带令牌")
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

        keeper(mapper).tick();

        assertEquals(1, chains.size(), "执行中的任务每拍都要续命");
        var values = chains.get(0).getParamNameValuePairs().values();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        assertTrue(values.contains(now.plus(LEASE)), "租约没续到 现在+5 分钟：" + values);
        assertTrue(values.contains(now), "心跳时间没更新：" + values);
        String where = chains.get(0).getCustomSqlSegment();
        assertTrue(where.contains("run_token"), "续命必须认令牌，否则旧执行会给新 attempt 续命：" + where);
        assertTrue(values.contains(TOKEN), "where 里没带本次令牌：" + values);
    }

    @Test
    @DisplayName("库里已取消：置位取消信号，不再续命")
    void detectsCancellation() {
        ResearchLeaseKeeper keeper =
            keeper(readOnlyMapper(row(HeronResearchTask.STATUS_CANCELLED)));

        keeper.tick();

        assertTrue(keeper.cancelled(), "取消没被探测到，研究就会继续烧钱");
        assertTrue(keeper.shouldStop(), "取消必须同时停掉工具调用");
    }

    /** 执行中收到取消：执行方还没退出，这一拍要停工具，但状态得留到它自己收尾。 */
    @Test
    @DisplayName("取消中：置位取消信号，不再续命")
    void detectsCancelling() {
        ResearchLeaseKeeper keeper =
            keeper(readOnlyMapper(row(HeronResearchTask.STATUS_CANCELLING)));

        keeper.tick();

        assertTrue(keeper.cancelled(), "cancelling 也要停，否则取消要等到执行结束才生效");
        assertFalse(keeper.lost(), "取消是用户要的，不是丢权");
    }

    /**
     * 这一行已经不是本次 attempt 了：重跑或回收都意味着执行权没了。
     * 此时唯一正确的动作是停止一切写操作——包括续命。
     */
    @Test
    @DisplayName("attempt 已被接管：判丢权，一次都不写")
    void losesWhenAttemptMovedOn() {
        HeronResearchTask stolen = row(HeronResearchTask.STATUS_RUNNING);
        stolen.setAttemptNo(ATTEMPT + 1);
        stolen.setRunToken("someone-else");

        ResearchLeaseKeeper keeper = keeper(readOnlyMapper(stolen));

        keeper.tick();

        assertTrue(keeper.lost(), "被接管了还当自己持权，就会继续给别人续命");
        assertFalse(keeper.cancelled());
        assertTrue(keeper.shouldStop(), "丢权后必须停掉工具调用");
    }

    /** 续命时条件更新拿 0 行：读到的行还像自己的，其实已经被改过。 */
    @Test
    @DisplayName("续命拿到 0 行：判丢权")
    void losesWhenRenewMatchesNothing() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(RESEARCH_ID)).thenReturn(row(HeronResearchTask.STATUS_RUNNING));
        when(mapper.update(any(Wrapper.class))).thenReturn(0);
        when(mapper.lambda()).thenAnswer(invocation ->
            new LambdaCrudChainWrapper<HeronResearchTask, ResearchTaskVo>(mapper));

        ResearchLeaseKeeper keeper = keeper(mapper);
        keeper.tick();

        assertTrue(keeper.lost(), "更新 0 行还当自己持权，结果就会覆盖别人");
    }

    @Test
    @DisplayName("已结束：既不续命也不置位")
    void ignoresFinishedRows() {
        ResearchLeaseKeeper keeper =
            keeper(readOnlyMapper(row(HeronResearchTask.STATUS_SUCCEEDED)));

        keeper.tick();

        assertFalse(keeper.cancelled());
        assertFalse(keeper.lost());
    }

    @Test
    @DisplayName("任务行没了：安静返回，不抛给研究线程")
    void toleratesMissingRow() {
        HeronResearchTaskMapper mapper = mock(HeronResearchTaskMapper.class);
        when(mapper.selectById(RESEARCH_ID)).thenReturn(null);
        ResearchLeaseKeeper keeper = keeper(mapper);

        assertDoesNotThrow(keeper::tick);
        assertFalse(keeper.cancelled());
        assertFalse(keeper.lost());
    }
}
