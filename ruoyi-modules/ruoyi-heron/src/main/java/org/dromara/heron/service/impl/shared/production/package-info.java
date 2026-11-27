/**
 * 跨线生产编排。
 *
 * <p>流水线骨架、生产状态机、调度、导演阶段与发布确认入口；四条视频线共用同一套实现，靠冻结快照的 {@code kind} 分支。
 */
package org.dromara.heron.service.impl.shared.production;
