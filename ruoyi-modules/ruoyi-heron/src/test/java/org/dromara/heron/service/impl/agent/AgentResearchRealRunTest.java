package org.dromara.heron.service.impl.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.heron.agentscope.HeronResearchTools;
import org.dromara.heron.domain.HeronDataOpportunity;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 一次真实研究：真模型、真工具协议、假数据。
 *
 * <p>只回答一个问题——模型拿到一条机会，会不会自己决定再查一次数据。
 * 数据是造的（不连库、不出网），因为要验的是循环与工具调用，不是取数正确性。
 *
 * <p>凭据与本项目其它真链路测试一致：{@code HERON_REAL_MODEL_KEY}。
 */
@Tag("render")
@DisplayName("研究层真跑：模型会不会主动多查一次")
class AgentResearchRealRunTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String resource(String path) {
        try (InputStream stream = AgentResearchRealRunTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }

    private static HeronDataOpportunity opportunity() {
        HeronDataOpportunity row = new HeronDataOpportunity();
        row.setOpportunityId(900L);
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
        row.setHeadline("澳元兑人民币近 7 日 -2.39%，跌破 30 日均值 1.8 个标准差");
        row.setSignals("{\"signal_type\":\"zscore\",\"change_pct\":-2.39,\"zscore\":-1.8,"
            + "\"abs_change\":-0.0114,\"change_scale\":0.05,\"coverage\":7,"
            + "\"merged_count\":7,\"merged_sources\":[\"frankfurter-audcny\"]}");
        return row;
    }

    private static DatasetFetchService datasetStub() {
        DatasetFetchService datasets = mock(DatasetFetchService.class);
        when(datasets.snapshot(anyString(), anyInt())).thenReturn(JSON.readTree("""
            {"contract":"dataset/1","shape":"wide","cadence":"daily",
             "retrieved_at":"2026-11-13T00:00:00Z","metric":"rate",
             "columns":[{"key":"value","label":"AUD/CNY","metric":"rate"}],
             "points":[
               {"date":"2026-11-06","value":0.4712},
               {"date":"2026-11-07","value":0.4708},
               {"date":"2026-11-08","value":0.4695},
               {"date":"2026-11-09","value":0.4681},
               {"date":"2026-11-10","value":0.4655},
               {"date":"2026-11-11","value":0.4637},
               {"date":"2026-11-12","value":0.4620},
               {"date":"2026-11-13","value":0.4599}
             ]}
            """));
        return datasets;
    }

    @Test
    @DisplayName("一条机会跑完整研究，看它用了哪些工具、最后给什么判断")
    void runsOneRealResearch() {
        String apiKey = System.getenv("HERON_REAL_MODEL_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 HERON_REAL_MODEL_KEY，真跑不静默跳过");
        }
        String baseUrl = System.getenv().getOrDefault("HERON_REAL_MODEL_BASE",
            "https://ark.cn-beijing.volces.com/api/coding/v3");
        String modelName = System.getenv().getOrDefault("HERON_REAL_MODEL", "deepseek-v4.1-flash-economy");

        DatasetFetchService datasets = datasetStub();
        HeronDataOpportunityMapper opportunities = mock(HeronDataOpportunityMapper.class);
        when(opportunities.selectById(900L)).thenReturn(opportunity());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new HeronResearchTools(datasets, opportunities,
            mock(org.dromara.heron.mapper.HeronDataSourceMapper.class),
            mock(org.dromara.heron.mapper.HeronVideoVersionMapper.class)));

        Model model = OpenAIChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .apiKey(apiKey)
            .build();

        try (HarnessAgent agent = HarnessAgent.builder()
            .name("heron-research")
            .sysPrompt(resource("/prompt/research/researcher.md"))
            .model(model)
            .toolkit(toolkit)
            .maxIters(8)
            .disableShellTool()
            .disableFilesystemTools()
            .disableWebTools()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableSessionPersistence()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .disableSubagents()
            .disableToolsConfig()
            .build()) {

            String goal = "机会 900 在候选里。判断它值不值得做成一条 60 秒的数据视频，"
                + "并说明你的依据。先看机会详情，拿不准就继续查。";

            Msg reply = agent.call(goal).block(Duration.ofMinutes(8));
            assertNotNull(reply, "模型没有返回");

            String text = reply.getTextContent();
            System.out.println("\n===== 研究输出开始 =====");
            System.out.println(text);
            System.out.println("===== 研究输出结束 =====\n");

            assertNotNull(text);
            assertFalse(text.isBlank());
            assertTrue(List.of("PRODUCE", "OBSERVE", "REJECT").stream().anyMatch(text::contains),
                "结论里应出现 PRODUCE / OBSERVE / REJECT 之一：" + text);

            // 它会用机会详情；会不会再查序列，就是这次要看的
            verify(opportunities, atLeastOnce()).selectById(900L);
            int seriesLookups = mockingDetails(datasets).getInvocations().size();
            System.out.println("query_trend_series 被调用次数（含 mock 其它调用）：" + seriesLookups);
        }
    }
}
