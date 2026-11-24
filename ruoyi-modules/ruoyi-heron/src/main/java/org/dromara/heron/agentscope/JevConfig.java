package org.dromara.heron.agentscope;

import io.agentscope.extensions.judge.jev.JevClient;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.system.api.ConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * JEV 外部判断客户端装配。默认关闭：不开 {@code heron.jev.enabled} 时不建 Bean，
 * 应用行为与引入依赖前完全一致。
 *
 * <p>开关与 {@link AgentRuntimeConfig} 的 {@code heron.agent.enabled} 分开：研究 Agent 与
 * 外部判断是两件事，关掉一个不该牵连另一个。JevClient 只回答结构化判断（Noul/Choice/Score），
 * 不授予工具权限，也不证明业务操作成功，永不进授权路径。
 *
 * <p>默认端点指向 Command Code 的 SystemOne 兼容实现；换回 TypeSafe 官方只需改
 * {@code heron.jev.base-url} 与 {@code heron.jev.model}，不改代码。
 */
@Configuration
@ConditionalOnProperty(prefix = "heron.jev", name = "enabled", havingValue = "true")
public class JevConfig {

    private static final String DEFAULT_BASE_URL = "https://api.commandcode.ai/provider/v1";
    private static final String DEFAULT_MODEL = "typesafe/jev";

    @Bean
    public JevClient heronJevClient(ConfigService config, Environment environment) {
        String apiKey = setting(config, environment, "heron.jev.api-key", null);
        if (apiKey == null) {
            apiKey = environment.getProperty("CMD_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new HeronBusinessException(503, "jev_not_configured", "JEV 配置不完整：缺少 heron.jev.api-key");
        }
        return JevClient.builder()
            .apiKey(apiKey.trim())
            .baseUrl(setting(config, environment, "heron.jev.base-url", DEFAULT_BASE_URL))
            .model(setting(config, environment, "heron.jev.model", DEFAULT_MODEL))
            // 单个判断点 5 秒封顶，重试最坏 15 秒；再长就该走旁路而不是阻塞
            .timeout(Duration.ofSeconds(environment.getProperty("heron.jev.timeout-seconds", Long.class, 5L)))
            .build();
    }

    private static String setting(ConfigService config, Environment environment, String key, String fallback) {
        String value = config.getConfigValue(key);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
