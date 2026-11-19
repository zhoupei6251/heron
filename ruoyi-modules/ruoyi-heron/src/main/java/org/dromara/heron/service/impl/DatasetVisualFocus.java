package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronBusinessException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从已校验的事实引用决定画面重点，不从关键词或相同数字猜测论点。 */
final class DatasetVisualFocus {
    private static final Pattern REFERENCE = Pattern.compile("\\{\\{(facts\\.[^{}]+)}}");

    private DatasetVisualFocus() { }

    record Focus(String mode, List<String> refs, List<String> labels) { }

    static Focus forScene(JsonNode scene, JsonNode snapshot) {
        Set<String> refs = new LinkedHashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        Matcher reference = REFERENCE.matcher(scene.path("lead").asString("") + "。" + scene.path("body").asString(""));
        while (reference.find()) {
            String path = reference.group(1);
            JsonNode value = DatasetFacts.at(snapshot, path);
            if (value == null || !(value.isNumber() || value.isString())) {
                throw new HeronBusinessException(409, "dataset_focus_invalid", "画面依据无法解析：" + path);
            }
            String ref = path.substring("facts.".length());
            refs.add(ref);
            String[] parts = ref.split("\\.");
            String entityPath = parts[0].equals("leader") ? "facts.leader"
                : parts.length >= 3 && (parts[0].equals("items") || parts[0].equals("series"))
                    ? "facts." + parts[0] + "." + parts[1] : null;
            JsonNode entity = entityPath == null ? null : DatasetFacts.at(snapshot, entityPath);
            if (entity != null) {
                for (String key : List.of("label", "name", "location")) {
                    String label = entity.path(key).asString("");
                    if (!label.isBlank()) {
                        labels.add(label);
                        break;
                    }
                }
            }
        }
        String mode;
        if (refs.contains("change") || refs.contains("change_pct")) {
            mode = "change";
        } else if (refs.contains("spread") || refs.stream().anyMatch(ref -> ref.startsWith("first."))
            && refs.stream().anyMatch(ref -> ref.startsWith("last."))) {
            mode = "comparison";
        } else if (refs.stream().anyMatch(ref -> ref.startsWith("min.") || ref.startsWith("max."))) {
            mode = "range";
        } else if (refs.stream().anyMatch(ref -> ref.startsWith("last.") || ref.startsWith("first."))) {
            mode = "latest";
        } else if (refs.stream().anyMatch(ref -> ref.startsWith("leader."))) {
            mode = "leader";
        } else {
            mode = labels.isEmpty() ? "overview" : "item";
        }
        return new Focus(mode, List.copyOf(refs), List.copyOf(labels));
    }
}
