package org.dromara.heron.service;

import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.domain.vo.ProtoVersionVo;
import org.dromara.heron.service.impl.OpenAiModelClient;
import org.dromara.heron.service.impl.ProtoModelRp1Author;
import org.dromara.heron.service.ModelClientTest.ConfigStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RP/1 作者：契约进 system，冻结需求进 user")
class ProtoRp1AuthorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RecordingClient client;
    private ProtoModelRp1Author author;
    private String inputJson;

    @BeforeEach
    void setUp() {
        client = new RecordingClient();
        author = new ProtoModelRp1Author(client);
        inputJson = JSON.writeValueAsString(new ProtoVersionVo.InputSnapshot(7L, 3, 11L,
            "做一个两页打卡清单，首页列待办、次页录入", "iphone-17", "mobile-hifi", "c0ffee",
            Map.of("blank-rp9", "sha256:aa"), List.of("list-item", "card"), "model-1"));
    }

    @Test
    @DisplayName("system 提示内嵌 RP/1 创作契约与 target=9 口径")
    void embedsRp1AuthoringContract() {
        author.author(run());

        String system = client.systemPrompt;
        assertTrue(system.contains("rp/1 target=9"), system);
        assertTrue(system.contains("[fillxhug]"), system);
        assertTrue(system.contains("phone-shell"), system);
        assertTrue(system.contains("E_PROPERTY"), system);
    }

    @Test
    @DisplayName("user 提示带冻结需求与画布并把模型原文原样返回")
    void sendsFrozenRequirementAndReturnsModelText() {
        String rp1 = author.author(run());

        assertEquals("rp/1 target=9 name=\"打卡\"\n", rp1);
        assertEquals("rp1", client.stage);
        assertTrue(client.userPrompt.contains("两页打卡清单"), client.userPrompt);
        assertTrue(client.userPrompt.contains("iphone-17"), client.userPrompt);
        assertTrue(client.userPrompt.contains("mobile-hifi"), client.userPrompt);
        assertTrue(client.userPrompt.contains("list-item"), client.userPrompt);
    }

    private ProductionRunVo run() {
        return new ProductionRunVo(1L, ProductionBizType.prototype, 7L, 9L, 1, "token", inputJson, null);
    }

    /** 只记录提示词，不参与 HTTP。 */
    class RecordingClient extends OpenAiModelClient {

        private String stage;
        private String systemPrompt;
        private String userPrompt;

        RecordingClient() {
            super(new ConfigStub(Map.of()), new StandardEnvironment(), null);
        }

        @Override
        public String chat(ProductionRunVo run, String stage, String systemPrompt, String userPrompt) {
            this.stage = stage;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            return "rp/1 target=9 name=\"打卡\"\n";
        }
    }
}
