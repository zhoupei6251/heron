package org.dromara.heron.service;

import cn.hutool.core.lang.Dict;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.OpenAiModelClient;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.dromara.system.api.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("模型 client：一次 chat/completions 与失败语义")
class ModelClientTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> configValues = new HashMap<>();
    private final Map<String, Object> fallbacks = new HashMap<>();
    private final RecordingUsage usage = new RecordingUsage();
    private volatile String path;
    private volatile String authorization;
    private volatile String requestBody;
    private volatile int responseStatus = 200;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile String responseBody = """
        {"choices":[{"message":{"content":"rp/1 target=9 name=\\"清单\\"\\n"}}]}""";

    private OpenAiModelClient client;
    private ProductionRunVo run;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::reply);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        configValues.put("heron.model.base-url", baseUrl);
        configValues.put("heron.model.name", "unit-model");
        configValues.put("heron.model.api-key", "sk-test");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", fallbacks));
        client = new OpenAiModelClient(new ConfigStub(configValues), environment, usage);
        run = new ProductionRunVo(1L, ProductionBizType.prototype, 7L, 9L, 1, "token", "{}", null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("按 OpenAI 兼容形态请求并取回内容")
    void postsChatCompletionsAndReturnsContent() {
        String content = client.chat(run, "rp1", "创作契约", "写一个两页清单");

        assertEquals(1, requestCount.get());
        assertEquals("/chat/completions", path);
        assertEquals("Bearer sk-test", authorization);
        assertTrue(requestBody.contains("\"model\":\"unit-model\""), requestBody);
        assertTrue(requestBody.contains("创作契约"), requestBody);
        assertTrue(requestBody.contains("写一个两页清单"), requestBody);
        assertTrue(content.startsWith("rp/1 target=9"), content);
    }

    @Test
    @DisplayName("参数表留空时环境变量兜底生效")
    void fallsBackToEnvironment() {
        configValues.remove("heron.model.base-url");
        fallbacks.put("heron.model.base-url", baseUrl);

        assertTrue(client.chat(run, "rp1", "契约", "需求").startsWith("rp/1"));
    }

    @Test
    @DisplayName("三项配置缺一即判未配置，不发请求也不记账")
    void incompleteConfigurationFailsBeforeRequest() {
        configValues.remove("heron.model.api-key");

        HeronBusinessException error =
            assertThrows(HeronBusinessException.class, () -> client.chat(run, "rp1", "契约", "需求"));

        assertEquals("model_not_configured", error.getCode());
        assertEquals(503, error.getStatus());
        assertEquals(0, requestCount.get());
        assertEquals(0, usage.starts);
    }

    @Test
    @DisplayName("HTTP 非 2xx 与空内容都是 model 类失败，不重试")
    void httpErrorAndEmptyContentFailAsModel() {
        responseStatus = 500;
        responseBody = "boom";

        HeronBusinessException http =
            assertThrows(HeronBusinessException.class, () -> client.chat(run, "rp1", "契约", "需求"));
        assertEquals("model_failed", http.getCode());
        assertEquals(1, requestCount.get());
        assertEquals("model_failed", usage.errorCode);

        responseStatus = 200;
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
        HeronBusinessException empty =
            assertThrows(HeronBusinessException.class, () -> client.chat(run, "rp1", "契约", "需求"));
        assertEquals("model_failed", empty.getCode());
        assertEquals(2, requestCount.get());
        assertEquals(2, usage.fails);
        assertEquals(0, usage.completes);
    }

    @Test
    @DisplayName("连接失败也只发一次请求")
    void transportFailureFailsOnceAsModel() {
        server.stop(0);

        HeronBusinessException error =
            assertThrows(HeronBusinessException.class, () -> client.chat(run, "rp1", "契约", "需求"));

        assertEquals("model_failed", error.getCode());
        assertEquals(0, requestCount.get());
        assertEquals(1, usage.fails);
    }

    @Test
    @DisplayName("供应商上报的用量进账本，未上报保持空")
    void recordsReportedUsageAndKeepsUnreportedEmpty() {
        responseBody = """
            {"id":"gen-1","choices":[{"message":{"content":"rp/1\\n"}}],
             "usage":{"prompt_tokens":120,"completion_tokens":340}}""";
        client.chat(run, "rp1", "契约", "需求");

        assertEquals("rp1", usage.stage);
        assertEquals("unit-model", usage.model);
        assertEquals("127.0.0.1", usage.provider);
        assertEquals("gen-1", usage.providerCallId);
        assertEquals(120L, usage.inputTokens);
        assertEquals(340L, usage.outputTokens);

        responseBody = "{\"choices\":[{\"message\":{\"content\":\"rp/1\\n\"}}]}";
        client.chat(run, "rp1", "契约", "需求");

        assertNull(usage.inputTokens);
        assertNull(usage.outputTokens);
        assertNull(usage.providerCallId);
        assertEquals(2, usage.completes);
    }

    private void reply(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        path = exchange.getRequestURI().getPath();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 只需要 getConfigValue，其余方法在参数表读取路径上不会被调用。 */
    record ConfigStub(Map<String, String> values) implements ConfigService {

        @Override
        public String getConfigValue(String key) {
            return values.get(key);
        }

        @Override
        public Dict getConfigMap(String key) {
            return null;
        }

        @Override
        public List<Dict> getConfigArrayMap(String key) {
            return List.of();
        }

        @Override
        public <T> T getConfigObject(String key, Class<T> clazz) {
            return null;
        }

        @Override
        public <T> List<T> getConfigArray(String key, Class<T> clazz) {
            return List.of();
        }
    }

    /** 只记参数，不落库；落库语义由 ProductionUsageTest 覆盖。 */
    static class RecordingUsage extends ProductionUsageService {

        private int starts;
        private int completes;
        private int fails;
        private String stage;
        private String provider;
        private String model;
        private String providerCallId;
        private String errorCode;
        private Long inputTokens;
        private Long outputTokens;

        RecordingUsage() {
            super(null);
        }

        @Override
        public boolean start(ProductionRunVo run, String stage, String callId, String provider, String model) {
            starts++;
            this.stage = stage;
            this.provider = provider;
            this.model = model;
            return true;
        }

        @Override
        public boolean complete(ProductionRunVo run, String callId, String providerCallId,
                                Long inputTokens, Long outputTokens, long elapsedMs) {
            completes++;
            this.providerCallId = providerCallId;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            return true;
        }

        @Override
        public boolean fail(ProductionRunVo run, String callId, String errorCode, long elapsedMs) {
            fails++;
            this.errorCode = errorCode;
            return true;
        }
    }
}
