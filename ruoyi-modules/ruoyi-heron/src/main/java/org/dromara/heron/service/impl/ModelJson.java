package org.dromara.heron.service.impl;

/** 模型常把 JSON 包在 ```json 围栏里；解析前统一剥掉围栏，不猜正文。 */
final class ModelJson {

    private ModelJson() { }

    static String unwrap(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int end = trimmed.lastIndexOf("```");
        if (firstLine < 0 || end <= firstLine) return trimmed;
        return trimmed.substring(firstLine + 1, end).strip();
    }
}
