package org.dromara.heron.domain.bo;

/** 对已保存 revision 的持久幂等生产请求。 */
public record ProtoGenerateBo(String requestKey, long expectedRevision) { }
