package org.dromara.heron.domain.vo;

import org.dromara.common.json.config.JacksonConfig;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("任务接口 JSON 契约")
class ProductionJsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
        .addModule(new JacksonConfig().registerJavaTimeModule())
        .build();

    @Test
    @DisplayName("大整数 ID 输出字符串且不下发内部字段")
    void taskJsonUsesStringIdAndHidesInternalFields() {
        ProductionTaskVo task = new ProductionTaskVo();
        task.setId(9007199254740993L);
        task.setBizType(ProductionBizType.prototype);
        task.setStatus(ProductionStatus.queued);
        task.setArtifacts(List.of(new ProductionFileVo(
            "artifact-1", "rp", 1024L, "sha256", "application/octet-stream", true)));

        String json = mapper.writeValueAsString(task);

        // 主键统一叫 id（见 V61 迁移）：超出 JS 安全整数范围时仍要输出字符串，
        // 否则前端拿到的是被截断的浮点数，按 id 查回来会查不到。
        assertTrue(json.contains("\"id\":\"9007199254740993\""), json);
        assertFalse(json.contains("runToken"));
        assertFalse(json.contains("inputSnapshot"));
        assertFalse(json.contains("storageKey"));
    }
}
