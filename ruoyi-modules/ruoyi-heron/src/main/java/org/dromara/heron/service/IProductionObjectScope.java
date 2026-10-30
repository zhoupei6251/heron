package org.dromara.heron.service;

/**
 * 业务锚点归属查询，供提交事务在预留前完成对象范围校验。
 * 未实现该接口的业务线只能依赖预留后的二次校验。
 */
public interface IProductionObjectScope {

    long ownerOf(long bizId);
}
