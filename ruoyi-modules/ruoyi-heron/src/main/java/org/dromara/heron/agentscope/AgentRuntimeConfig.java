package org.dromara.heron.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.mapper.HeronAgentToolCallMapper;
import org.dromara.heron.mapper.HeronDataOpportunityMapper;
import org.dromara.heron.mapper.HeronDataSourceMapper;
import org.dromara.heron.mapper.HeronNewsMetricMapper;
import org.dromara.heron.mapper.HeronResearchSnapshotMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.dromara.heron.service.IResearchAgent;
import org.dromara.heron.service.impl.OpenAiModelClient;
import org.dromara.heron.service.impl.DatasetFetchService;
import org.dromara.system.api.ConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;

/**
 * 研究层 Agent 装配。默认关闭：不开 {@code heron.agent.enabled} 时应用行为与引入依赖前完全一致。
 *
 * <p>危险能力逐项关掉，对应 {@code docs/plans/plan.md} §6.3 的安全边界：
 * shell、文件系统、联网、记忆、会话落盘、工作区、子 Agent、外部工具配置。
 * 少关一项就等于给模型留了一个越权入口，所以这里逐项列出而不是依赖默认值。
 */
@Configuration
@ConditionalOnProperty(prefix = "heron.agent", name = "enabled", havingValue = "true")
public class AgentRuntimeConfig {


    /** 沿用 heron.model.* 三项配置，不另起一套模型配置。 */
    @Bean
    public Model heronAgentModel(ConfigService config, Environment environment) {
        String baseUrl = setting(config, environment, "heron.model.base-url");
        String name = setting(config, environment, "heron.model.name");
        String apiKey = setting(config, environment, "heron.model.api-key");
        if (baseUrl == null || name == null || apiKey == null) {
            throw new HeronBusinessException(503, "model_not_configured", "模型配置不完整");
        }
        return OpenAIChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(name)
            .apiKey(apiKey)
            .build();
    }

    @Bean
    public HeronResearchTools heronResearchTools(DatasetFetchService datasets,
                                                 HeronDataOpportunityMapper opportunities,
                                                 HeronDataSourceMapper sources,
                                                 HeronVideoVersionMapper videos,
                                                 ObjectProvider<HeronNewsMetricMapper> metrics) {
        // 历史表现是可选依赖：没有它时 query_published_performance 会如实说不可用，
        // 不该让整个 Agent 装配失败
        HeronResearchTools tools = new HeronResearchTools(datasets, opportunities, sources, videos);
        HeronNewsMetricMapper metricMapper = metrics.getIfAvailable();
        return metricMapper == null ? tools : tools.withMetrics(metricMapper);
    }

    /** 状态存储单独成 Bean：构造时会校验表，测试里可以用内存实现替掉，不碰库。 */
    @Bean
    @ConditionalOnMissingBean
    public AgentStateStore heronAgentStateStore(DataSource dataSource) {
        return stateStore(dataSource);
    }

    /**
     * 研究 Agent 按会话创建，不是单例：会话标识在构建时定死，单例就会让所有研究共用一段上下文。
     *
     * <p>状态落 MySQL（迁移 {@code 032-agent-state.sql}），不在部署机留目录，多副本可共用。
     */
    @Bean
    public ResearchAgentFactory heronResearchAgentFactory(Model heronAgentModel,
                                                          HeronResearchTools heronResearchTools,
                                                          Environment environment,
                                                          AgentStateStore heronAgentStateStore) {
        return new ResearchAgentFactory(heronAgentModel, heronResearchTools, heronAgentStateStore,
            environment.getProperty("heron.agent.max-iters", Integer.class, 8),
            // 单个取数工具卡住会吃掉整个调查窗口，框架默认 5 分钟太长
            Duration.ofSeconds(environment.getProperty("heron.agent.tool-timeout-seconds", Long.class, 10L)));
    }

    /** 业务侧只认这个端口，不认 AgentScope。 */
    @Bean
    public IResearchAgent heronResearchAgent(ResearchAgentFactory heronResearchAgentFactory,
                                             HeronAgentToolCallMapper toolCallMapper,
                                             HeronDataOpportunityMapper opportunityMapper,
                                             HeronResearchSnapshotMapper snapshotMapper,
                                             OpenAiModelClient modelClient,
                                             Environment environment,
                                             ObjectProvider<Clock> clocks) {
        return new AgentscopeResearchAgent(heronResearchAgentFactory, toolCallMapper, opportunityMapper,
            snapshotMapper, modelClient,
            clocks.getIfAvailable(Clock::systemDefaultZone),
            environment.getProperty("heron.agent.max-tool-calls", Integer.class, 12),
            environment.getProperty("heron.agent.max-tokens", Long.class, 300_000L),
            Duration.ofSeconds(environment.getProperty("heron.agent.max-duration-seconds", Long.class, 300L)));
    }

    /**
     * 状态表由迁移 {@code 032-agent-state.sql} 建好，运行时不再建表；
     * 库名取连接自身的 catalog，避免把库名写死在代码里。
     */
    static AgentStateStore stateStore(DataSource dataSource) {
        String catalog;
        try (Connection connection = dataSource.getConnection()) {
            catalog = connection.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据库名失败，无法配置 AgentState 存储", e);
        }
        return new MysqlAgentStateStore(dataSource, catalog, "heron_agent_state", false);
    }

    private static String setting(ConfigService config, Environment environment, String key) {
        String value = config.getConfigValue(key);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(key);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }
}
