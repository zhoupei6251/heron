package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronResearchTask;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronResearchTaskMapper;
import org.dromara.heron.service.IResearchAgent;
import org.dromara.heron.service.impl.shared.research.ResearchTaskServiceImpl;
import org.dromara.heron.support.HeronMysqlTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 执行隔离的真库验证：旧执行者不能覆盖新 attempt 的结果。
 *
 * <p>为什么必须打真库：P0 修复的核心声明是「条件更新拿 0 行就说明执行权没了」。
 * mock 一个 wrapper 只能证明「我构造了这些条件」，证明不了「数据库真的按这些条件拒绝」。
 * 这条链一旦失效，表现是旧执行的结论静默覆盖掉新执行的结论——不会报错，只会给出错的判断。
 */
@Tag("mysql")
@DisplayName("研究执行隔离：旧执行者无法覆盖新 attempt")
class ResearchExecutionFencingTest extends HeronMysqlTestSupport {

    private static final long RESEARCH_ID = 7_001L;

    @Autowired private HeronResearchTaskMapper researchTaskMapper;

    @BeforeEach
    void researchSchema() throws SQLException {
        resetSchema();
    }

    @Override
    protected void extendSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // 父类的 DROP 列表只覆盖生产表，研究表由这里自己清；
            // 每个用例都要从空表开始，否则第二个用例会撞「表已存在」
            statement.execute("DROP TABLE IF EXISTS heron_research_task");
            // 与 script/sql/heron/033+036+037+040 对齐；测试库只建这一张
            statement.execute("""
                CREATE TABLE heron_research_task (
                  research_id    bigint       NOT NULL,
                  research_no    varchar(32)  NOT NULL,
                  opportunity_id bigint       NULL,
                  owner_id       bigint       NOT NULL,
                  actor_id       bigint       NOT NULL,
                  goal           varchar(1000) NOT NULL,
                  snapshot_ref   varchar(256) NULL,
                  grade          varchar(4)   NOT NULL DEFAULT 'L2',
                  status         varchar(16)  NOT NULL,
                  decision_type  varchar(16)  NULL,
                  score          decimal(6,2) NULL,
                  confidence     decimal(5,4) NULL,
                  rounds         int          NOT NULL DEFAULT 0,
                  tool_calls     int          NOT NULL DEFAULT 0,
                  input_tokens   bigint       NULL,
                  output_tokens  bigint       NULL,
                  session_id     varchar(128) NULL,
                  prompt_version char(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  attempt_no     int          NOT NULL DEFAULT 0,
                  run_token      varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  lease_until    datetime(3)  NULL,
                  heartbeat_at   datetime(3)  NULL,
                  result_text    longtext     NULL,
                  evidence_json  json         NULL,
                  report_json    json         NULL,
                  error_code     varchar(64)  NULL,
                  error_reason   varchar(2000) NULL,
                  dedup_key      char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                  create_dept    bigint NULL, create_by bigint NULL, create_time datetime(3) NOT NULL,
                  update_by      bigint NULL, update_time datetime(3) NOT NULL,
                  PRIMARY KEY (research_id),
                  UNIQUE KEY uk_research_no (research_no),
                  UNIQUE KEY uk_research_dedup (dedup_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
    }

    private void insertQueued() {
        jdbc.update("""
            INSERT INTO heron_research_task
              (research_id, research_no, opportunity_id, owner_id, actor_id, goal, grade, status,
               attempt_no, session_id, dedup_key, create_time, update_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,now(3),now(3))
            """,
            RESEARCH_ID, "R" + RESEARCH_ID, 9_00L, 1L, 1L, "机会 900 在候选里。", "L2",
            HeronResearchTask.STATUS_QUEUED, 0, "research-" + RESEARCH_ID, "dedup-" + RESEARCH_ID);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Clock> noClock() {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.dromara.heron.service.IContentDecisionService> noDecisions() {
        ObjectProvider<org.dromara.heron.service.IContentDecisionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private ResearchTaskServiceImpl service(IResearchAgent agent) {
        return new ResearchTaskServiceImpl(researchTaskMapper,
            mock(HeronDataOpportunityMapper.class), agent, noClock(), noDecisions());
    }

    private HeronResearchTask row() {
        return researchTaskMapper.selectById(RESEARCH_ID);
    }

    /**
     * 两次执行交叠：A 先领到第 1 次，租约过期后 B 领走第 2 次；
     * A 随后才跑完并尝试写回。A 的结论必须被数据库拒绝，不能覆盖 B 的。
     */
    @Test
    @DisplayName("旧执行者跑完后的写回被拒，库里留下新 attempt 的结果")
    void oldExecutorCannotOverwriteNewAttempt() throws Exception {
        insertQueued();

        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        // A 执行到一半被挂住，模拟「超时但还没真停」
        IResearchAgent agentA = mock(IResearchAgent.class);
        when(agentA.research(any())).thenAnswer(invocation -> {
            aStarted.countDown();
            assertTrue(releaseA.await(30, TimeUnit.SECONDS), "A 没有被放行");
            return new IResearchAgent.Outcome("A 的结论", 3, 2, 10L, 10L, false, false, null);
        });
        CompletableFuture<String> runA = CompletableFuture.supplyAsync(() -> service(agentA).run(RESEARCH_ID));

        assertTrue(aStarted.await(30, TimeUnit.SECONDS), "A 没有领到任务");
        assertEquals(1, row().getAttemptNo(), "A 应拿到第 1 次执行");

        // 租约过期：模拟 A 卡死，被别人回收
        jdbc.update("UPDATE heron_research_task SET lease_until = ? WHERE research_id = ?",
            LocalDateTime.now().minusMinutes(10), RESEARCH_ID);

        IResearchAgent agentB = mock(IResearchAgent.class);
        when(agentB.research(any())).thenReturn(
            new IResearchAgent.Outcome("B 的结论", 5, 3, 20L, 20L, false, false, null));
        assertEquals(HeronResearchTask.STATUS_SUCCEEDED, service(agentB).run(RESEARCH_ID),
            "B 应领走过期的第 2 次执行");

        String tokenB = row().getRunToken();
        assertEquals(2, row().getAttemptNo(), "库里应是第 2 次执行");
        assertEquals("B 的结论", row().getResultText());

        // 现在才放行 A——它手里是第 1 次的令牌
        releaseA.countDown();
        assertNotNull(runA.get(60, TimeUnit.SECONDS));

        HeronResearchTask after = row();
        assertEquals(2, after.getAttemptNo(), "A 的写回不该把 attempt 拨回去");
        assertEquals("B 的结论", after.getResultText(), "旧执行者的结论覆盖了新执行的，隔离失效");
        assertEquals(tokenB, after.getRunToken(), "令牌被动过，说明写回条件没生效");
        assertEquals(HeronResearchTask.STATUS_SUCCEEDED, after.getStatus());
    }

    /**
     * 取消后重跑的完整路径：执行中的任务先落 cancelling，执行方失联后由调度器收尾成
     * cancelled，再经重排回队列，新 attempt 才被领走。旧执行随后醒来，只能退场。
     *
     * <p>这条同时验证了「cancelling 期间不允许新 attempt 领取」——
     * 取消请求发出后、执行方还没退出之前，库里不会凭空多出第二份执行。
     */
    @Test
    @DisplayName("取消后重跑：旧执行者醒来也改不动新 attempt")
    void cancelledOldRunDoesNotCancelNewAttempt() throws Exception {
        insertQueued();

        CountDownLatch releaseA = new CountDownLatch(1);
        IResearchAgent agentA = mock(IResearchAgent.class);
        when(agentA.research(any())).thenAnswer(invocation -> {
            assertTrue(releaseA.await(30, TimeUnit.SECONDS), "A 没有被放行");
            return new IResearchAgent.Outcome("A 的结论", 3, 2, 10L, 10L, false, false, null);
        });
        CompletableFuture<String> runA = CompletableFuture.supplyAsync(() -> service(agentA).run(RESEARCH_ID));
        for (int i = 0; i < 200 && row().getAttemptNo() != 1; i++) {
            Thread.sleep(25);
        }
        assertEquals(1, row().getAttemptNo(), "A 应拿到第 1 次执行");

        // 取消：执行中落 cancelling，此时不该被任何人领走
        service(mock(IResearchAgent.class)).cancel(RESEARCH_ID);
        assertEquals(HeronResearchTask.STATUS_CANCELLING, row().getStatus());
        IResearchAgent intruder = mock(IResearchAgent.class);
        service(intruder).run(RESEARCH_ID);
        verify(intruder, never()).research(any());
        assertEquals(HeronResearchTask.STATUS_CANCELLING, row().getStatus(),
            "cancelling 期间被领走了，取消等于没生效");

        // 执行方失联：租约过期后由调度器收尾成终态
        jdbc.update("UPDATE heron_research_task SET lease_until = ? WHERE research_id = ?",
            LocalDateTime.now().minusMinutes(10), RESEARCH_ID);
        assertEquals(1, service(mock(IResearchAgent.class)).resolveAbandonedCancels(),
            "失联的取消没有被收尾，这条任务会永远停在 cancelling");
        assertEquals(HeronResearchTask.STATUS_CANCELLED, row().getStatus());

        // 重排（requeue 的状态回退效果）后新 attempt 才能领
        jdbc.update("UPDATE heron_research_task SET status = ? WHERE research_id = ?",
            HeronResearchTask.STATUS_QUEUED, RESEARCH_ID);
        IResearchAgent agentB = mock(IResearchAgent.class);
        when(agentB.research(any())).thenReturn(
            new IResearchAgent.Outcome("B 的结论", 5, 3, 20L, 20L, false, false, null));
        service(agentB).run(RESEARCH_ID);
        assertEquals(2, row().getAttemptNo(), "B 应领走第 2 次");
        assertEquals("B 的结论", row().getResultText());

        // 旧执行此刻才醒来：它手里是第 1 次的令牌，且它自己感知到过取消
        releaseA.countDown();
        runA.get(60, TimeUnit.SECONDS);

        HeronResearchTask after = row();
        assertEquals(2, after.getAttemptNo(), "旧执行把 attempt 拨回去了");
        assertEquals("B 的结论", after.getResultText(), "旧执行把新 attempt 的结果改掉了");
        assertEquals(HeronResearchTask.STATUS_SUCCEEDED, after.getStatus(),
            "新 attempt 已成功，不该被旧执行的取消收尾改成 cancelled");
    }
}
