package org.dromara.common.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.web.filter.RepeatedlyRequestWrapper;
import org.dromara.common.web.interceptor.PlusWebInvokeTimeInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求参数日志的脱敏。
 *
 * <p>为什么这条要钉死：SSE 推送用 EventSource 发请求，带不了请求头，
 * 前端只能把会话令牌放进 URL 查询参数。这份参数一旦原样打进日志，
 * 任何人拿到日志文件就持有一份能直接用的登录令牌——
 * 实测里 admin 的会话令牌就是这么进了 logs-dev.out，同时被存进了 sys_oper_log。
 */
@DisplayName("common-web 请求日志脱敏")
class RequestLogRedactionTest {

    /** 一段形状与真实令牌一致的样例，只用于验证它不会出现在日志里。 */
    private static final String FAKE_TOKEN = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";

    /** 最小 Spring 容器只初始化一次。 */
    private static boolean contextInitialized;

    /**
     * 验证查询参数里的令牌不进日志，同一请求里的普通参数仍然留着。
     */
    @Test
    @DisplayName("查询参数里的会话令牌不进日志")
    void shouldNotLogTokenFromQueryParams() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resource/message");
        request.setServletPath("/resource/message");
        request.setParameter("Authorization", FAKE_TOKEN);
        request.setParameter("clientid", "e5cd7e4891bf95d1d19206ce24a7b32e");

        String logged = captureRequestLog(request);

        assertFalse(logged.contains("eyJhbGciOiJIUzI1NiJ9"), "会话令牌不许进日志");
        assertFalse(logged.contains(FAKE_TOKEN), "整个令牌值不许进日志");
        assertTrue(logged.contains("clientid"), "非敏感参数仍然要留在日志里");
    }

    /**
     * 验证请求体里的令牌也不进日志。
     */
    @Test
    @DisplayName("请求体里的令牌不进日志")
    void shouldNotLogTokenFromJsonBody() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/some/api");
        raw.setServletPath("/some/api");
        raw.setContentType("application/json");
        raw.setContent(("{\"Authorization\":\"" + FAKE_TOKEN + "\",\"name\":\"x\"}")
            .getBytes(StandardCharsets.UTF_8));

        String logged = captureRequestLog(new RepeatedlyRequestWrapper(raw, new MockHttpServletResponse()));

        assertFalse(logged.contains("eyJhbGciOiJIUzI1NiJ9"), "会话令牌不许进日志");
    }

    /**
     * 跑一次拦截器，把「开始请求」那行日志取出来。
     *
     * @param request 请求
     * @return 日志内容
     * @throws Exception 拦截器签名声明的异常
     */
    private static String captureRequestLog(HttpServletRequest request) throws Exception {
        initializeJsonContext();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(PlusWebInvokeTimeInterceptor.class);
        logger.addAppender(appender);
        try {
            new PlusWebInvokeTimeInterceptor()
                .preHandle(request, new MockHttpServletResponse(), new Object());
            return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("开始请求"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有捕获到请求日志"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * JsonUtils 静态初始化时要从 Spring 容器里拿全局 JsonMapper，
     * 这里给一个只注册了 JsonMapper 的最小容器。
     */
    private static void initializeJsonContext() {
        if (contextInitialized) {
            return;
        }
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.refresh();
        new SpringUtils().setApplicationContext(context);
        contextInitialized = true;
    }
}
