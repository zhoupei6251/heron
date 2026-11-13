package org.dromara.heron.service.impl;

import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentScope 接入探针：只回答两个问题，不涉及业务。
 *
 * <p>一、{@code @Tool} 的 schema 派生能否工作——这是接入时唯一靠依赖树查不出来的一点，
 * 曾经因为 victools 5.0（Jackson 3）与 4.x（Jackson 2）的返回类型不同而失败过。
 *
 * <p>二、内置的 shell / 文件系统工具能否被真正关掉——这两个能力被 plan.md §6.3 明令禁止，
 * 关不掉就不能用这个框架。
 */
@DisplayName("AgentScope 探针：工具 schema 与危险内置工具")
class AgentToolSchemaProbeTest {

    /** 与研究层将来要写的只读工具同形状：显式参数、有界返回。 */
    public static class ProbeTools {

        @Tool(name = "query_trend_series", description = "取某实体某指标的时间序列", readOnly = true)
        public String queryTrendSeries(
            @ToolParam(name = "entity", description = "实体标识", required = true) String entity,
            @ToolParam(name = "metric", description = "指标名", required = true) String metric,
            @ToolParam(name = "limit", description = "最多返回几个点", required = false) int limit) {
            return "[]";
        }
    }

    /** 探针不调真模型：只验证装配与工具注册，stream 永不发元素。 */
    private static Model silentModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "probe-model";
            }
        };
    }

    @Test
    @DisplayName("@Tool 能派生出带参数的 schema")
    void derivesSchemaFromJavaSignature() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());

        assertEquals(Set.of("query_trend_series"), toolkit.getToolNames(), "注册一个工具就只应看到一个");

        List<ToolSchema> schemas = toolkit.getToolSchemas();
        assertEquals(1, schemas.size());
        ToolSchema schema = schemas.get(0);
        assertEquals("query_trend_series", schema.getName());
        assertNotNull(schema.getParameters(), "参数 schema 为空说明 victools 版本不兼容");
        assertTrue(schema.getParameters().containsKey("properties"), "schema 里应有 properties：" + schema.getParameters());
        assertTrue(String.valueOf(schema.getParameters()).contains("entity"), "应包含入参名：" + schema.getParameters());
    }

    @Test
    @DisplayName("危险内置工具（shell / 文件系统）能被关掉")
    void dangerousBuiltinToolsCanBeDisabled() {
        Toolkit ours = new Toolkit();
        ours.registerTool(new ProbeTools());

        try (HarnessAgent agent = HarnessAgent.builder()
            .name("probe")
            .model(silentModel())
            .toolkit(ours)
            .maxIters(1)
            .disableShellTool()
            .disableFilesystemTools()
            .disableWebTools()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .build()) {

            ReActAgent delegate = agent.getDelegate();
            Set<String> names = delegate.getToolkit().getToolNames();
            assertTrue(names.contains("query_trend_series"), "自己注册的只读工具应该还在：" + names);

            List<String> dangerous = names.stream()
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.contains("shell") || lower.contains("bash") || lower.contains("exec")
                        || lower.contains("file") || lower.contains("write") || lower.contains("edit");
                })
                .toList();
            assertTrue(dangerous.isEmpty(), "关闭后仍有危险工具： " + dangerous + "；全部工具=" + names);
        }
    }
}
