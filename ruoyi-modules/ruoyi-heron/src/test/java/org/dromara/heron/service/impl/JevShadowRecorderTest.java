package org.dromara.heron.service.impl;

import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import org.dromara.heron.domain.HeronJevShadow;
import org.dromara.heron.mapper.HeronJevShadowMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SHADOW 对照的三条硬约定：不配就不动、失败记未定、一致才算 agree。
 */
@DisplayName("JEV SHADOW：不配即零动作，失败不当作同意")
class JevShadowRecorderTest {

    private final HeronJevShadowMapper mapper = mock(HeronJevShadowMapper.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);

    @Test
    @DisplayName("没配 JEV：不起线程、不落库")
    void inertWithoutClient() {
        ObjectProvider<JevClient> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(null);
        JevShadowRecorder recorder = recorder(clients, new MockEnvironment());

        recorder.record(pools(), decision("DATA_BOARD"));

        verifyNoInteractions(executor, mapper);
    }

    @Test
    @DisplayName("选线一致时 agree 为真，并记下置信度")
    void agreesOnSameLine() {
        JevClient client = mock(JevClient.class);
        when(client.systemOneBlocking(any()))
            .thenReturn(new SystemOneResult("typesafe/jev",
                Map.of("line", new ChoiceAnswer("DATA_BOARD", Map.of("DATA_BOARD", 0.9), 0.9)), null));
        JevShadowRecorder recorder = recorder(clients(client), new MockEnvironment());

        recorder.record(pools(), decision("DATA_BOARD"));

        HeronJevShadow row = captured();
        assertThat(row.getStatus()).isEqualTo(HeronJevShadow.STATUS_OK);
        assertThat(row.getJevLine()).isEqualTo("DATA_BOARD");
        assertThat(row.getJevConfidence()).isEqualTo(0.9);
        assertThat(row.getAgree()).isTrue();
    }

    @Test
    @DisplayName("选线不一致时 agree 为假，但同样不影响主链")
    void disagreesOnOtherLine() {
        JevClient client = mock(JevClient.class);
        when(client.systemOneBlocking(any()))
            .thenReturn(new SystemOneResult("typesafe/jev",
                Map.of("line", new ChoiceAnswer("NO_PUBLISH", Map.of("NO_PUBLISH", 0.7), 0.7)), null));
        JevShadowRecorder recorder = recorder(clients(client), new MockEnvironment());

        assertThatCode(() -> recorder.record(pools(), decision("DATA_BOARD"))).doesNotThrowAnyException();

        assertThat(captured().getAgree()).isFalse();
    }

    @Test
    @DisplayName("调用失败记未定，不抛回主链")
    void failureIsUndetermined() {
        JevClient client = mock(JevClient.class);
        when(client.systemOneBlocking(any())).thenThrow(new IllegalStateException("boom"));
        JevShadowRecorder recorder = recorder(clients(client), new MockEnvironment());

        assertThatCode(() -> recorder.record(pools(), decision("DATA_BOARD"))).doesNotThrowAnyException();

        HeronJevShadow row = captured();
        assertThat(row.getStatus()).isEqualTo(HeronJevShadow.STATUS_UNDETERMINED);
        assertThat(row.getErrorCode()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("超出日预算不调用，记未定")
    void budgetExhaustedSkipsCall() {
        JevClient client = mock(JevClient.class);
        MockEnvironment environment = new MockEnvironment().withProperty("heron.jev.daily-call-limit", "0");
        JevShadowRecorder recorder = recorder(clients(client), environment);

        recorder.record(pools(), decision("DATA_BOARD"));

        HeronJevShadow row = captured();
        assertThat(row.getStatus()).isEqualTo(HeronJevShadow.STATUS_UNDETERMINED);
        assertThat(row.getErrorCode()).isEqualTo("daily_budget");
        verify(client, never()).systemOneBlocking(any());
    }

    @Test
    void inadmissibleLineIsNotRecordedAsAValidJudgment() {
        JevClient client = mock(JevClient.class);
        when(client.systemOneBlocking(any())).thenReturn(new SystemOneResult("test-jev",
            Map.of("line", new ChoiceAnswer("KNOWLEDGE_EXPLAINER", Map.of("KNOWLEDGE_EXPLAINER", 1.0), 1.0)), null));
        recorder(clients(client), new MockEnvironment()).record(pools(), decision("DATA_BOARD"));
        assertThat(captured().getStatus()).isEqualTo(HeronJevShadow.STATUS_UNDETERMINED);
    }

    /** 同步执行器：把异步改成当场跑，测试才不用等线程。 */
    @SuppressWarnings("unchecked")
    private JevShadowRecorder recorder(ObjectProvider<JevClient> clients, MockEnvironment environment) {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).submit(any(Runnable.class));
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable(any(Supplier.class))).thenReturn(Clock.systemUTC());
        return new JevShadowRecorder(clients, mapper, executor, environment, clocks);
    }

    private HeronJevShadow captured() {
        ArgumentCaptor<HeronJevShadow> captor = ArgumentCaptor.forClass(HeronJevShadow.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JevClient> clients(JevClient client) {
        ObjectProvider<JevClient> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(client);
        return clients;
    }

    private static EditorialDecisionService.Pools pools() {
        return new EditorialDecisionService.Pools("pool-1", "0".repeat(64),
            List.of(), List.of(), List.of(new EditorialDecisionService.DatasetRef("test", "测试数据源")),
            List.of(), List.of(), List.of(), List.of());
    }

    private static EditorialDecisionService.Decision decision(String line) {
        return new EditorialDecisionService.Decision("PUBLISH", line, "理由", "意图", null, null);
    }
}
