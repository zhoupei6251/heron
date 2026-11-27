package org.dromara.heron.service.impl.shared.support;

/** 模型常把 JSON 包在 ```json 围栏里；解析前统一剥掉围栏，不猜正文。 */
public final class ModelJson {

    private ModelJson() { }

    /**
     * 去掉模型输出外层的 ```json 围栏并 strip；没有围栏或围栏不完整时原样返回（strip 后）。
     *
     * @return 可直接交给 JSON 解析器的文本；入参为 null 时返回 null
     */
    public static String unwrap(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int end = trimmed.lastIndexOf("```");
        if (firstLine < 0 || end <= firstLine) return trimmed;
        return trimmed.substring(firstLine + 1, end).strip();
    }
}
