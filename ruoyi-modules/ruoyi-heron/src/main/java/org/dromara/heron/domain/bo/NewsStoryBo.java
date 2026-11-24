package org.dromara.heron.domain.bo;

import java.util.Map;

/**
 * 人工编辑策划结论的请求。
 *
 * <p>策划结论按 story/1 的 JSON 形状原样收：字段多且互相嵌套（主角、张力、主张、落点、受影响方），
 * 拆成平铺的字段反而要让页面自己拼装一套平铺结构再翻译回去。
 *
 * @param requestKey 幂等键
 * @param expectedRevision 草稿版本号，防止覆盖别人的改动
 * @param story 编辑后的策划结论 story/1
 */
public record NewsStoryBo(String requestKey, Long expectedRevision, Map<String, Object> story) { }
