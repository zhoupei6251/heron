package org.dromara.heron.config;

import io.agentscope.extensions.judge.jev.JevClient;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.system.api.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * JEV 装配测试：开关关着不建 Bean，开着且配了 key 才建，缺 key 启动即失败。
 *
 * <p>最后一条是重点：JEV 判断失败或未配置一律不得默认放行，也不许静默降级。
 */
@DisplayName("JEV 装配：默认关闭、缺 key 直接拒绝")
class JevConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(JevConfig.class)
        .withBean(ConfigService.class, () -> mock(ConfigService.class));

    @Test
    @DisplayName("不开开关时不装配 JevClient")
    void inertWhenDisabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(JevClient.class));
    }

    @Test
    @DisplayName("开关打开且配了 key 时建出客户端")
    void buildsClientWhenConfigured() {
        runner.withPropertyValues("heron.jev.enabled=true", "heron.jev.api-key=probe-key")
            .run(context -> assertThat(context).hasSingleBean(JevClient.class));
    }

    @Test
    @DisplayName("开关打开但缺 key：启动失败并给出稳定错误码")
    void rejectsMissingKey() {
        runner.withPropertyValues("heron.jev.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(HeronBusinessException.class);
            assertThat(((HeronBusinessException) root).getCode()).isEqualTo("jev_not_configured");
        });
    }
}
