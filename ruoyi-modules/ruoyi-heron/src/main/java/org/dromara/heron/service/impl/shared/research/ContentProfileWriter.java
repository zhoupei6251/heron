package org.dromara.heron.service.impl.shared.research;

import org.dromara.heron.service.impl.lane.databoard.DatasetTitleAuthor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * 内容属性补充：只有生产期才知道的那两项。
 *
 * <p>标题钩子类型在写稿时产生、脚本定稿后就没了；开头结构可以从定稿的脚本读出来。
 * 其余归因属性都能从冻结快照或现有表直接取到，不再存第二份。
 *
 * <p>取不到就整个字段不写：归因侧统一按「未记录」处理，不猜不补。
 */
public final class ContentProfileWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SCHEMA_VERSION = "content-profile/1";
    private static final Pattern REFERENCE = Pattern.compile("\\{\\{facts\\.[^{}]+}}");

    private ContentProfileWriter() {
    }

    /**
     * 组一份内容属性。
     *
     * @param hookType 选中标题的钩子类型；不是契约里的五类时视为取不到
     * @param script   最终稿
     * @return content-profile/1；两项都取不到时返回 null
     */
    public static ObjectNode write(String hookType, JsonNode script) {
        ObjectNode profile = JsonNodeFactory.instance.objectNode();
        profile.put("schemaVersion", SCHEMA_VERSION);
        boolean any = false;
        if (hookType != null && !hookType.isBlank() && DatasetTitleAuthor.KINDS.contains(hookType)) {
            profile.put("hookType", hookType);
            any = true;
        }
        ObjectNode opening = opening(script);
        if (opening != null) {
            profile.set("opening", opening);
            any = true;
        }
        if (!any) {
            return null;
        }
        profile.put("writtenAt", LocalDateTime.now(ZoneOffset.UTC).toString());
        return profile;
    }

    /**
     * 开头结构。
     *
     * <p>四类里只把两类算得出来：有事实引用就是数据优先，首句是问句就是提问优先；
     * 另外两类（主张优先、背景优先）要读语义才分得出来，判不了就不写整个字段——
     * 猜一个标签进去，归因那边就会拿它当结论用。
     */
    static ObjectNode opening(JsonNode script) {
        JsonNode scenes = script.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            return null;
        }
        JsonNode first = scenes.get(0);
        String lead = first.path("lead").asString("");
        String body = first.path("body").asString("");
        String opening = (lead + body).strip();
        if (opening.isBlank()) {
            return null;
        }
        boolean hasFact = REFERENCE.matcher(opening).find();
        boolean hasQuestion = lead.contains("？") || lead.contains("?");
        String shape;
        if (hasFact) {
            shape = "data_first";
        } else if (hasQuestion) {
            shape = "question_first";
        } else {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("shape", shape);
        node.put("hasFact", hasFact);
        node.put("hasQuestion", hasQuestion);
        return node;
    }

    /** 序列化成字符串，便于直接落库。 */
    public static String writeJson(String hookType, JsonNode script) {
        ObjectNode profile = write(hookType, script);
        return profile == null ? null : JSON.writeValueAsString(profile);
    }
}
