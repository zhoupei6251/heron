package org.dromara.heron.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 标题专用调用：按五类钩子各出一个候选，再由写稿调用结合数据选一个。
 * 方向、长度、禁语在代码里硬校验，不指望模型自觉。
 */
@Service
@RequiredArgsConstructor
public class DatasetTitleAuthor {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SYSTEM = resource("/prompt/data-board/title-contract.md");
    private static final String STAGE = "dataset-title";
    private static final List<String> BANNED = List.of(
        "必涨", "必跌", "稳赚", "抄底", "逃顶", "预测", "将涨", "将跌", "一定", "保证");

    private final OpenAiModelClient model;

    public List<String> candidates(ProductionRunVo run, String datasetJson) {
        JsonNode snapshot = JSON.readTree(datasetJson);
        String pack = ColumnPacks.text(snapshot.path("source_key").asString(""));
        String reply = model.chat(run, STAGE, SYSTEM + "\n\n---\n\n" + pack, prompt(snapshot));
        return parse(reply, snapshot);
    }

    static List<String> parse(String reply, JsonNode snapshot) {
        JsonNode node;
        try {
            node = JSON.readTree(ModelJson.unwrap(reply));
        } catch (RuntimeException e) {
            throw contract("标题输出不是 JSON");
        }
        JsonNode candidates = node.path("candidates");
        if (!candidates.isArray()) throw contract("缺少 candidates");
        List<String> valid = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            String title = candidate.path("title").asString("").strip();
            if (!title.isEmpty() && valid(title, snapshot) && !valid.contains(title)) {
                valid.add(title);
            }
        }
        if (valid.size() < 3) {
            throw contract("有效标题不足 3 个：" + valid);
        }
        return List.copyOf(valid.subList(0, Math.min(5, valid.size())));
    }

    static boolean valid(String title, JsonNode snapshot) {
        if (title == null || title.isBlank()) return false;
        int precision = snapshot.path("metric").path("precision").asInt(2);
        String resolved;
        try {
            resolved = DatasetPlaceholders.fill(title, snapshot, precision);
        } catch (RuntimeException e) {
            return false;
        }
        int length = resolved.codePointCount(0, resolved.length());
        if (length < 4 || length > 15) return false;
        for (String banned : BANNED) {
            if (title.contains(banned)) return false;
        }
        double change = snapshot.path("facts").path("change").asDouble(0);
        boolean up = containsAny(title, "涨", "升", "走高", "新高", "反弹", "上行");
        boolean down = containsAny(title, "跌", "降", "走低", "新低", "回落", "下行");
        if (up && change < 0) return false;
        return !down || change <= 0;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static String prompt(JsonNode snapshot) {
        return "数据快照：\n" + snapshot + "\n按契约输出五个候选，每个 kind 一个。";
    }

    private static HeronBusinessException contract(String reason) {
        return new HeronBusinessException(409, "dataset_title_contract", "标题退回重写：" + reason);
    }

    private static String resource(String path) {
        try (InputStream stream = DatasetTitleAuthor.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少提示词资源 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资源不可读 " + path, e);
        }
    }
}
