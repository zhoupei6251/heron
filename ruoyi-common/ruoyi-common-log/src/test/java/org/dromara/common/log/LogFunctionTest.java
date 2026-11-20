package org.dromara.common.log;

import cn.hutool.extra.spring.SpringUtil;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.aspect.LogAspect;
import org.dromara.common.log.enums.BusinessStatus;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.log.enums.OperatorType;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.satoken.utils.LoginHelper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@DisplayName("common-log 功能单元测试")
class LogFunctionTest {

    private static final AtomicReference<OperLogEvent> LAST_EVENT = new AtomicReference<>();

    /** 一段形状与真实令牌一致的样例，只用于验证它不会被记录下来。 */
    private static final String FAKE_TOKEN = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";

    /**
     * 初始化日志切面依赖的 JSON 映射器和事件容器，避免启动完整 Spring 应用。
     */
    @BeforeAll
    static void initializeLogInfrastructure() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("jsonMapper", JsonMapper.builder().build());
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof OperLogEvent operLog) {
                LAST_EVENT.set(operLog);
            }
        });
        context.refresh();
        new SpringUtil().setApplicationContext(context);
    }

    /**
     * 为每个日志切面测试绑定独立 HTTP 请求并清空上一次捕获的事件。
     */
    @BeforeEach
    void bindRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/system/users");
        request.addHeader(LoginHelper.CLIENT_KEY, "web-client");
        request.addHeader("X-Forwarded-For", "10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        LAST_EVENT.set(null);
    }

    /**
     * 清理线程请求上下文，避免日志切面测试污染后续用例。
     */
    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 验证上传文件、Servlet 请求及包含这些对象的集合会从日志参数中排除。
     */
    @Test
    @DisplayName("识别不可记录的请求参数")
    void shouldFilterServletAndUploadObjects() {
        LogAspect aspect = new LogAspect();
        MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", new byte[]{1});
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertTrue(aspect.isFilterObject(file));
        assertTrue(aspect.isFilterObject(request));
        assertTrue(aspect.isFilterObject(new Object[]{"value", file}));
        assertTrue(aspect.isFilterObject(List.of("value", file)));
        assertTrue(aspect.isFilterObject(Map.of("file", file)));
        assertFalse(aspect.isFilterObject(List.of("value", 1L)));
    }

    /**
     * 验证操作日志注解保存业务类型、操作人类型及请求响应开关。
     *
     * @throws Exception 读取测试方法注解失败
     */
    @Test
    @DisplayName("读取操作日志注解配置")
    void shouldExposeLogAnnotationConfiguration() throws Exception {
        Method method = TestController.class.getDeclaredMethod("update");
        Log log = method.getAnnotation(Log.class);

        assertEquals("用户管理", log.title());
        assertEquals(BusinessType.UPDATE, log.businessType());
        assertEquals(OperatorType.MOBILE, log.operatorType());
        assertFalse(log.isSaveRequestData());
        assertTrue(log.isSaveResponseData());
        assertArrayEquals(new String[]{"password"}, log.excludeParamNames());
    }

    /**
     * 验证日志切面执行目标方法后发布完整事件，并从序列化请求参数中排除密码等敏感字段。
     */
    @Test
    @DisplayName("记录成功操作日志并排除敏感参数")
    void shouldPublishSuccessfulOperationLogWithFilteredParameters() throws Throwable {
        Log annotation = TestController.class.getDeclaredMethod("create", CreateRequest.class).getAnnotation(Log.class);
        ProceedingJoinPoint joinPoint = joinPoint(new CreateRequest("alice", "secret"), Map.of("id", 1L));

        Object result;
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getLoginUser).thenReturn(null);
            result = new LogAspect().doAround(joinPoint, annotation);
        }

        OperLogEvent event = LAST_EVENT.get();
        assertEquals(Map.of("id", 1L), result);
        assertNotNull(event);
        assertEquals(BusinessStatus.SUCCESS.ordinal(), event.getStatus());
        assertEquals("新增用户", event.getTitle());
        assertEquals("POST", event.getRequestMethod());
        assertEquals("/system/users", event.getOperUrl());
        assertEquals("10.0.0.8", event.getOperIp());
        assertEquals("web-client", event.getClientKey());
        assertTrue(event.getOperParam().contains("alice"));
        assertFalse(event.getOperParam().contains("secret"));
        assertTrue(event.getJsonResult().contains("\"id\":1"));
    }

    /**
     * 验证查询参数里的会话令牌不进操作日志。
     *
     * <p>SSE 推送用 EventSource 发请求，带不了请求头，前端只能把令牌放进 URL 查询参数。
     * 这一份原样存进 sys_oper_log，就等于把一份能直接登录的令牌交出去了。
     *
     * @throws Throwable 切面调用失败
     */
    @Test
    @DisplayName("查询参数里的会话令牌不进操作日志")
    void shouldNotPersistTokenFromQueryParams() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resource/message");
        request.setParameter("Authorization", FAKE_TOKEN);
        request.setParameter("clientid", "e5cd7e4891bf95d1d19206ce24a7b32e");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Log annotation = TestController.class.getDeclaredMethod("create", CreateRequest.class).getAnnotation(Log.class);
        ProceedingJoinPoint joinPoint = joinPoint(new CreateRequest("alice", "secret"), Map.of("id", 1L));

        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getLoginUser).thenReturn(null);
            new LogAspect().doAround(joinPoint, annotation);
        }

        OperLogEvent event = LAST_EVENT.get();
        assertNotNull(event);
        assertFalse(event.getOperParam().contains("eyJhbGciOiJIUzI1NiJ9"), "会话令牌不许进操作日志");
        assertTrue(event.getOperParam().contains("clientid"), "非敏感参数仍然要留在操作日志里");
    }

    /**
     * 创建可返回指定结果的切点，并提供日志方法名、目标类和请求参数。
     *
     * @param request 请求参数
     * @param result  目标方法返回值
     * @return 模拟切点
     */
    private static ProceedingJoinPoint joinPoint(CreateRequest request, Object result) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("create");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new TestController());
        when(joinPoint.getArgs()).thenReturn(new Object[]{request});
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    private static class TestController {

        /**
         * 提供完整日志注解配置供反射测试读取。
         */
        @Log(title = "用户管理", businessType = BusinessType.UPDATE, operatorType = OperatorType.MOBILE,
            isSaveRequestData = false, excludeParamNames = "password")
        private void update() {
        }

        /**
         * 提供保存请求和响应数据的日志配置供完整切面测试。
         *
         * @param request 新增请求
         */
        @Log(title = "新增用户", businessType = BusinessType.INSERT, excludeParamNames = "password")
        private void create(CreateRequest request) {
        }
    }

    private record CreateRequest(String username, String password) {
    }
}
