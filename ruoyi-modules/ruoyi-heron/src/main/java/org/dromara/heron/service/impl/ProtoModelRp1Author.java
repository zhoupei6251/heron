package org.dromara.heron.service.impl;

import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.domain.vo.ProtoVersionVo;
import org.dromara.heron.service.ProtoRp1Author;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * RP/1 由模型自由写：提示词内嵌引擎创作契约，模型原文直接交给装配流水线校验，失败不重试。
 */
@Service
public class ProtoModelRp1Author implements ProtoRp1Author {

    private static final String CONTRACT = contract();

    private final OpenAiModelClient model;

    public ProtoModelRp1Author(OpenAiModelClient model) {
        this.model = model;
    }

    @Override
    public String author(ProductionRunVo run) {
        ProtoVersionVo.InputSnapshot input = ProtoProductionDomain.readInput(run.inputJson());
        // 项目主题是 Heron 的 default，引擎只认自己的主题名；原样传过去会在 check 阶段报 E_THEME。
        String theme = "mobile".equals(input.canvas()) ? "mobile-hifi" : "enterprise-light";
        StringBuilder prompt = new StringBuilder()
            .append("需求：").append(input.requirement()).append('\n')
            .append("画布：").append(input.canvas()).append('\n')
            .append("首行固定写 domain=").append(input.canvas()).append(" theme=").append(theme).append("。\n")
            .append("硬性规则（违反任何一条都会被 check 拒绝）：\n")
            .append("mobile".equals(input.canvas())
                ? "页面声明写 device=iphone-17。\n"
                : "页面声明不要写 device（device 只用于移动端）。\n")
            .append("- 节点类型只能用下面列出的清单。\n")
            .append("- 分隔线用 divider [fillx1]；所有节点宽高必须是正数。\n")
            .append("- 子元素必须落在父容器边界内（超出 2px 会被拒）。\n");
        if (input.supportedWidgets() != null && !input.supportedWidgets().isEmpty()) {
            prompt.append("只用这些元件：").append(String.join("、", input.supportedWidgets())).append('\n');
        }
        prompt.append("按契约输出完整 .rp1 源码。");
        return model.chat(run, "rp1", CONTRACT, prompt.toString());
    }

    private static String contract() {
        try (InputStream resource = ProtoModelRp1Author.class.getResourceAsStream(
            "/prompt/prototype/rp1-authoring-contract.md")) {
            if (resource == null) {
                throw new IllegalStateException("缺少 rp1-authoring-contract.md");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("RP/1 创作契约不可读", e);
        }
    }
}
