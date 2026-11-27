package org.dromara.heron.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronPublishInventory;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.bo.PublishInventoryQuery;
import org.dromara.heron.domain.vo.PublishInventoryVo;
import org.dromara.heron.domain.vo.PublishReservationVo;
import org.dromara.heron.mapper.HeronPublishInventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布库存池：所有视频线成片的统一出口。
 *
 * <p>入池在版本完成事务里发生——任务成功即进库存，不存在「生产成功却没入池」。
 * 评估与发布是后续独立阶段；淘汰只改状态，产物与历史全保留。
 */
@Service
@RequiredArgsConstructor
public class PublishInventoryService {

    private final HeronPublishInventoryMapper inventoryMapper;

    /**
     * 入池硬性门禁 + 建库存条目。调用方是版本完成事务（MANDATORY）。
     *
     * <p>门禁失败抛出：事实/审稿/视觉闸没过的版本根本不进库存，
     * 这是硬性准入，不是评分加减项。同一作品的新版本入池时，旧条目标 SUPERSEDED。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admit(HeronVideoVersion version, long workId, Long candidateId, ProductionBizType line,
                      String editorialReview, String qualityReport) {
        NewsWorkServiceImpl.checkEditorialPass(editorialReview);
        NewsWorkServiceImpl.checkVisualPass(qualityReport);
        inventoryMapper.lambda()
            .set(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_SUPERSEDED)
            .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
            .eq(HeronPublishInventory::getWorkId, workId)
            .in(HeronPublishInventory::getPublishStatus,
                HeronPublishInventory.PUBLISH_WAITING, HeronPublishInventory.PUBLISH_RESERVED)
            .eq(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_ELIGIBLE)
            .update();
        HeronPublishInventory row = new HeronPublishInventory();
        row.setId(IdUtil.getSnowflakeNextId());
        row.setVersionId(version.getId());
        row.setWorkId(workId);
        row.setCandidateId(candidateId);
        row.setBizLine(line.name());
        row.setDurationMs(version.getDurationMs());
        row.setCurationStatus(HeronPublishInventory.CURATION_PENDING);
        row.setPublishStatus(HeronPublishInventory.PUBLISH_WAITING);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        if (inventoryMapper.insert(row) != 1) {
            throw new HeronBusinessException(409, "inventory_admit_failed", "发布库存写入失败");
        }
    }

    /** 待评估条目：AI 总编辑的输入。 */
    public List<HeronPublishInventory> pendingCuration(int limit) {
        return inventoryMapper.selectList(inventoryMapper.lambda()
            .eq(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_PENDING)
            .orderByAsc(HeronPublishInventory::getCreateTime).last("LIMIT " + limit).build());
    }

    /** 写回一次评估结论：保留/暂缓/淘汰 + 评分明细。 */
    @Transactional(rollbackFor = Exception.class)
    public void curate(HeronPublishInventory row, String curationStatus, Integer priorityScore,
                       String scoreBreakdown, String reason, String curatorVersion,
                       LocalDateTime expiresAt) {
        int changed = inventoryMapper.lambda()
            .set(HeronPublishInventory::getCurationStatus, curationStatus)
            .set(priorityScore != null, HeronPublishInventory::getPriorityScore, priorityScore)
            .set(scoreBreakdown != null, HeronPublishInventory::getScoreBreakdown, scoreBreakdown)
            .set(HeronPublishInventory::getCurationReason, reason)
            .set(HeronPublishInventory::getCuratorVersion, curatorVersion)
            .set(HeronPublishInventory::getEvaluatedAt, LocalDateTime.now())
            .set(expiresAt != null, HeronPublishInventory::getExpiresAt, expiresAt)
            .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
            .eq(HeronPublishInventory::getId, row.getId())
            .eq(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_PENDING)
            .updateCount();
        if (changed != 1) {
            throw new HeronBusinessException(409, "inventory_curate_conflict", "库存条目已不是待评估状态");
        }
    }

    /**
     * 自动与人工共用的原子预占：单条条件更新从 waiting 翻到 reserved。
     *
     * <p>并发同时点击时只有一个 updateCount 命中，天然原子；只有达到
     * 最低分的 eligible+waiting 条目可被预占，宁缺毋滥。
     */
    @Transactional(rollbackFor = Exception.class)
    public HeronPublishInventory reserve(Long inventoryId, int minScore) {
        expireOverdue();
        int changed = inventoryMapper.lambda()
            .set(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_RESERVED)
            .set(HeronPublishInventory::getReservedAt, LocalDateTime.now())
            .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
            .eq(HeronPublishInventory::getId, inventoryId)
            .eq(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_ELIGIBLE)
            .eq(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_WAITING)
            .ge(HeronPublishInventory::getPriorityScore, minScore)
            .updateCount();
        if (changed != 1) {
            throw new HeronBusinessException(409, "inventory_reserve_conflict", "库存条目已被预占或不达发布线");
        }
        return inventoryMapper.selectById(inventoryId);
    }

    /** 当前最高分的可预占条目；发布调度器先取它再原子预占。 */
    public HeronPublishInventory topEligible(int minScore) {
        expireOverdue();
        return inventoryMapper.selectOne(inventoryMapper.lambda()
            .eq(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_ELIGIBLE)
            .eq(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_WAITING)
            .ge(HeronPublishInventory::getPriorityScore, minScore)
            .orderByDesc(HeronPublishInventory::getPriorityScore)
            .last("LIMIT 1").build());
    }

    /** 把过期未发布的合格条目转 EXPIRED；定时器调用。 */
    public int expireOverdue() {
        return inventoryMapper.lambda()
            .set(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_EXPIRED)
            .set(HeronPublishInventory::getUpdateTime, LocalDateTime.now())
            .eq(HeronPublishInventory::getCurationStatus, HeronPublishInventory.CURATION_ELIGIBLE)
            .eq(HeronPublishInventory::getPublishStatus, HeronPublishInventory.PUBLISH_WAITING)
            .isNotNull(HeronPublishInventory::getExpiresAt)
            .lt(HeronPublishInventory::getExpiresAt, LocalDateTime.now())
            .updateCount();
    }

    /** 评分明细 JSON：各维 score 求和即总分；求和为 0 说明明细无效。 */
    static int totalOf(JsonNode breakdown) {
        int total = 0;
        for (JsonNode item : breakdown) {
            total += item.path("score").asInt(0);
        }
        return total;
    }

    /** 待发布池分页视图；默认只看合格待发布。 */
    public PageResult<PublishInventoryVo> pageList(PublishInventoryQuery query) {
        String curation = query.getCurationStatus() == null || query.getCurationStatus().isBlank()
            ? HeronPublishInventory.CURATION_ELIGIBLE : query.getCurationStatus();
        var wrapper = QueryBuilder.lambda(HeronPublishInventory.class)
            .eq(HeronPublishInventory::getCurationStatus, curation)
            .eqIfText(HeronPublishInventory::getPublishStatus, query.getPublishStatus())
            .eqIfText(HeronPublishInventory::getBizLine, query.getBizLine())
            .eqIfPresent(HeronPublishInventory::getWorkId, query.getWorkId())
            .orderByDesc(HeronPublishInventory::getPriorityScore, HeronPublishInventory::getId).build();
        Page<PublishInventoryVo> page = inventoryMapper.selectVoPage(query.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /** 人工立即发布：预占后交既有发布链；评分线放宽到 0——人工可越过排序，不能越过硬闸（入池已过）。 */
    public PublishReservationVo reserveForManual(long inventoryId) {
        HeronPublishInventory reserved = reserve(inventoryId, 0);
        return new PublishReservationVo(reserved.getId(), reserved.getWorkId(), reserved.getVersionId(),
            reserved.getBizLine());
    }
}
