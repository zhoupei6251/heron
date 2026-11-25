package org.dromara.heron.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallLedgerTest {

    @Test
    void recordsOnlySuccessfulToolNamesForEvidenceValidation() {
        ToolCallLedger ledger = new ToolCallLedger(1L, 1, mock(HeronAgentToolCallMapper.class),
            Clock.systemUTC(), new ResearchBudget(10, 10_000));
        ToolUseBlock read = ToolUseBlock.builder().id("1").name("read_breakdown").input(Map.of()).build();
        ToolUseBlock frame = ToolUseBlock.builder().id("2").name("view_frame").input(Map.of()).build();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test-agent");

        ledger.onEvent(new PostActingEvent(agent, null, read, ToolResultBlock.text("ok"))).block();
        ledger.onEvent(new PostActingEvent(agent, null, frame, ToolResultBlock.error("bad image"))).block();

        assertEquals(Set.of("read_breakdown"), ledger.successfulTools());
    }
}
