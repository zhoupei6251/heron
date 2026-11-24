package org.dromara.heron.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.dromara.heron.domain.HeronAgentToolCall;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 核验真跑用的固定装置：真模型 + 假数据。
 *
 * <p>数据是造的（不连库、不出网），因为要验的是「核验员的判断力」，不是取数正确性。
 * 凭据与项目其它真链路测试一致：{@code HERON_REAL_MODEL_KEY}，缺了直接失败不静默跳过。
 */
final class VerifierProbeSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private VerifierProbeSupport() {
    }

    record Env(ResearchAgentFactory factory, HeronAgentToolCallMapper ledgerMapper) {
    }

    static Env prepare() {
        String apiKey = System.getenv("HERON_REAL_MODEL_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 HERON_REAL_MODEL_KEY，真跑不静默跳过");
        }

        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(JSON.readTree("""
            {"contract":"dataset/1","shape":"wide","cadence":"daily",
             "retrieved_at":"2026-11-13T00:00:00Z","metric":{"key":"rate","unit":"CNY"},
             "columns":[{"key":"value","label":"AUD/CNY","metric":"rate"}],
             "rows":[{"date":"2026-11-06","value":0.4712},{"date":"2026-11-07","value":0.4708},
                     {"date":"2026-11-08","value":0.4695},{"date":"2026-11-09","value":0.4681},
                     {"date":"2026-11-10","value":0.4655},{"date":"2026-11-11","value":0.4637},
                     {"date":"2026-11-12","value":0.4620},{"date":"2026-11-13","value":0.4599}]}
            """));

        HeronDataOpportunityMapper opportunities = mock(HeronDataOpportunityMapper.class);
        HeronDataOpportunity row = new HeronDataOpportunity();
        row.setId(900L);
        row.setSourceKey("frankfurter-audcny");
        row.setEntity("AUD/CNY");
        row.setMetric("rate");
        row.setShape("wide");
        row.setCadence("daily");
        row.setWindowPoints(7);
        row.setPoints(7);
        row.setPeriodStart("2026-11-06");
        row.setPeriodEnd("2026-11-13");
        row.setScore(BigDecimal.valueOf(69.94));
        row.setStatus("new");
        row.setHeadline("澳元兑人民币近 7 日 -2.39%");
        row.setSignals("{\"signal_type\":\"zscore\",\"zscore\":-1.8}");
        when(opportunities.selectById(900L)).thenReturn(row);

        HeronResearchTools tools = new HeronResearchTools(datasets, opportunities,
            mock(HeronDataSourceMapper.class), mock(HeronVideoVersionMapper.class));

        HeronAgentToolCallMapper ledgerMapper = mock(HeronAgentToolCallMapper.class);
        when(ledgerMapper.insert(any(HeronAgentToolCall.class))).thenReturn(1);

        Model model = OpenAIChatModel.builder()
            .baseUrl(System.getenv().getOrDefault("HERON_REAL_MODEL_BASE",
                "https://ark.cn-beijing.volces.com/api/coding/v3"))
            .modelName(System.getenv().getOrDefault("HERON_REAL_MODEL", "deepseek-v4.1-flash-economy"))
            .apiKey(apiKey)
            .build();

        return new Env(new ResearchAgentFactory(model, tools, new InMemoryAgentStateStore(), 8,
            Duration.ofSeconds(10)), ledgerMapper);
    }
}
