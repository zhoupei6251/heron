package org.dromara.heron.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.dromara.heron.service.impl.OpenAiModelClient;
import org.dromara.system.api.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 装配测试：用轻量上下文验证 {@link AgentRuntimeConfig} 真能建出 Agent，不启动整个应用。
 *
 * <p>两件事必须成立：开关关着时一个 Bean 都不建（应用行为与引入依赖前一致），
 * 开关打开时建出来的 Agent 只带我们注册的只读工具。
 */
@DisplayName("Agent 装配：默认关闭、开启后只挂只读工具")
class AgentRuntimeConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AgentRuntimeConfig.class)
        .withBean(ConfigService.class, () -> {
            ConfigService config = mock(ConfigService.class);
            when(config.getConfigValue("heron.model.base-url")).thenReturn("http://127.0.0.1:9/v1");
            when(config.getConfigValue("heron.model.name")).thenReturn("probe-model");
            when(config.getConfigValue("heron.model.api-key")).thenReturn("probe-key");
            return config;
        })
        .withBean(DatasetFetchService.class, () -> mock(DatasetFetchService.class))
        .withBean(HeronDataOpportunityMapper.class, () -> mock(HeronDataOpportunityMapper.class))
        .withBean(HeronDataSourceMapper.class, () -> mock(HeronDataSourceMapper.class))
        .withBean(HeronVideoVersionMapper.class, () -> mock(HeronVideoVersionMapper.class))
        .withBean(HeronAgentToolCallMapper.class, () -> mock(HeronAgentToolCallMapper.class))
        // L1 档走轻量模型客户端，装配时需要有这个 Bean
        .withBean(OpenAiModelClient.class,
            () -> mock(OpenAiModelClient.class, org.mockito.Mockito.withSettings().lenient()))
        // 存储换成内存实现：MySQL 那个构造时要校验表，单测不该连库
        .withBean(AgentStateStore.class, InMemoryAgentStateStore::new);

    @Test
    @DisplayName("不开开关时不装配任何 Agent 相关 Bean")
    void inertWhenDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ResearchAgentFactory.class);
            assertThat(context).doesNotHaveBean(Model.class);
        });
    }

    @Test
    @DisplayName("开关打开后能按会话创建 Agent，工具集只有注册的只读工具")
    void buildsAgentWhenEnabled() {
        runner.withPropertyValues("heron.agent.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ResearchAgentFactory.class);

            ResearchAgentFactory factory = context.getBean(ResearchAgentFactory.class);
            try (HarnessAgent agent = factory.create("research-test")) {
                ReActAgent delegate = agent.getDelegate();
                Set<String> names = delegate.getToolkit().getToolNames();
                assertThat(names)
                    .contains("query_opportunities", "query_opportunity_detail", "query_trend_series",
                        "query_dataset_snapshot", "compare_series", "query_data_sources",
                        "query_source_health", "query_recent_productions", "submit_findings")
                    .allSatisfy(name -> assertThat(name.toLowerCase())
                        .doesNotContain("shell", "bash", "file", "write", "edit"));
            }
        });
    }

    @Test
    @DisplayName("会话标识为空直接拒绝：空标识等于所有研究共用一段上下文")
    void rejectsBlankSessionId() {
        runner.withPropertyValues("heron.agent.enabled=true").run(context -> {
            ResearchAgentFactory factory = context.getBean(ResearchAgentFactory.class);
            assertThatThrownBy(() -> factory.create(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话标识");
        });
    }
}
