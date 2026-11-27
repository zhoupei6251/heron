package org.dromara.heron.service.impl.shared.support;

import cn.hutool.core.util.IdUtil;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.impl.shared.production.ProductionUsageService;
import org.dromara.system.api.ConfigService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容模型入口：一次 chat/completions，用量进账本，失败不重试。
 */
@Service
public class OpenAiModelClient {

    /** 模型写稿比引擎慢，超时按整段生成给足；失败由人点重跑，不自动重试。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(300);
    private static final String FAILURE = "model_failed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ConfigService config;
    private final Environment environment;
    private final ProductionUsageService usage;
    private final HttpClient http;

    public OpenAiModelClient(ConfigService config, Environment environment, ProductionUsageService usage) {
        this.config = config;
        this.environment = environment;
        this.usage = usage;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * 带任务上下文的对话调用：使用默认模型，成功/失败都写入用量账本。
     *
     * @return 模型文本
     * @throws HeronBusinessException 503 {@code model_not_configured} 配置不完整；
     *         503 {@code model_failed} HTTP 非 2xx、输出为空、被中断或其他调用异常（先记账再抛）
     */
    public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
        return request(run, stage, modelName(), systemPrompt, userPrompt);
    }

    /**
     * 视觉审稿模型名。
     *
     * @return 配置的模型名；未配置返回 null，调用方据此退化为不评画面/纯文本审稿
     */
    public String visionReviewModel() {
        return setting("heron.model.quality-vision-name");
    }

    /**
     * 视觉审查调用：把 PNG 以 base64 data URL 附在用户消息里，交给指定视觉模型。
     *
     * @param visualModel 视觉模型名（来自 {@link #visionReviewModel()}）
     * @return 模型文本
     * @throws HeronBusinessException 配置不完整或调用失败（同 {@link #chat}）
     */
    public String chatWithContactSheet(ProductionRunVo run, String stage, String visualModel,
                                String systemPrompt, String userPrompt, byte[] png) {
        return request(run, stage, visualModel, systemPrompt, List.of(
            Map.of("type", "text", "text", userPrompt),
            Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)))));
    }

    private String request(ProductionRunVo run, String stage, String model, String systemPrompt, Object userContent) {
        String baseUrl = setting("heron.model.base-url");
        String apiKey = setting("heron.model.api-key");
        if (baseUrl == null || model == null || apiKey == null) {
            throw new HeronBusinessException(503, "model_not_configured", "模型配置不完整");
        }
        String callId = IdUtil.getSnowflakeNextIdStr();
        usage.start(run, stage, callId, host(baseUrl), model);
        long began = System.nanoTime();
        String payload = payload(model, systemPrompt, userContent);
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/chat/completions"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        String content;
        Long inputTokens = null;
        Long outputTokens = null;
        String providerCallId = null;
        try {
            HttpResponse<String> result =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (result.statusCode() / 100 != 2) {
                throw failed(run, callId, began, "模型返回 HTTP " + result.statusCode(), null);
            }
            JsonNode root = JSON.readTree(result.body());
            JsonNode message = root.path("choices").path(0).path("message").path("content");
            if (!message.isString() || message.asString().isBlank()) {
                throw failed(run, callId, began, "模型输出为空", null);
            }
            content = message.asString();
            if (root.path("id").isString()) {
                providerCallId = root.path("id").asString();
            }
            JsonNode reported = root.path("usage");
            if (reported.path("prompt_tokens").isNumber()) {
                inputTokens = reported.path("prompt_tokens").asLong();
            }
            if (reported.path("completion_tokens").isNumber()) {
                outputTokens = reported.path("completion_tokens").asLong();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(run, callId, began, "模型调用被中断", e);
        } catch (HeronBusinessException e) {
            throw e;
        } catch (Exception e) {
            throw failed(run, callId, began, "模型调用失败: " + e.getClass().getSimpleName(), e);
        }
        usage.complete(run, callId, providerCallId, inputTokens, outputTokens, elapsedMs(began));
        return content;
    }

    /**
     * 没有任务上下文的轻量调用（例如挑排版模板）：不写用量表，失败照常抛。
     * 用量台账只记成片生产，不记运维时的选择动作。
     */
    public String chatWithoutUsage(String systemPrompt, String userPrompt) {
        String baseUrl = setting("heron.model.base-url");
        String model = setting("heron.model.name");
        String apiKey = setting("heron.model.api-key");
        if (baseUrl == null || model == null || apiKey == null) {
            throw new HeronBusinessException(503, "model_not_configured", "模型配置不完整");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/chat/completions"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(payload(model, systemPrompt, userPrompt), StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> result = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (result.statusCode() / 100 != 2) {
                throw new HeronBusinessException(503, FAILURE, "模型返回 HTTP " + result.statusCode());
            }
            JsonNode message = JSON.readTree(result.body()).path("choices").path(0).path("message").path("content");
            if (!message.isString() || message.asString().isBlank()) {
                throw new HeronBusinessException(503, FAILURE, "模型输出为空");
            }
            return message.asString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HeronBusinessException(503, FAILURE, "模型调用被中断");
        } catch (HeronBusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new HeronBusinessException(503, FAILURE, "模型调用失败: " + e.getClass().getSimpleName());
        }
    }

    private static String payload(String model, String systemPrompt, Object userContent) {
        return JSON.writeValueAsString(Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent))));
    }

    /** 部署侧要记录"这条结果由哪个模型档位产出"，只给逻辑名，不外泄密钥。 */
    public String modelName() {
        return setting("heron.model.name");
    }

    /** 参数表优先，环境变量与 yml 兜底；空白等同于未配置。 */
    private String setting(String key) {
        String value = config.getConfigValue(key);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(key);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 失败先记账再抛：账本状态由 ProductionRunner 的 unknown 兜底接管。 */
    private HeronBusinessException failed(ProductionRunVo run, String callId, long began,
                                          String reason, Exception cause) {
        usage.fail(run, callId, FAILURE, elapsedMs(began));
        HeronBusinessException error = new HeronBusinessException(503, FAILURE, reason);
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    private static long elapsedMs(long beganNanos) {
        return Math.max((System.nanoTime() - beganNanos) / 1_000_000, 0);
    }

    private static String host(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trimSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
