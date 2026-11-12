package org.dromara.heron.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronAutoSlot;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronNewsWork;
import org.dromara.heron.domain.HeronVideoVersion;
import org.dromara.heron.domain.bo.EditorialRunQuery;
import org.dromara.heron.domain.vo.EditorialRunVo;
import org.dromara.heron.mapper.HeronAutoSlotMapper;
import org.dromara.heron.mapper.HeronNewsWorkMapper;
import org.dromara.heron.mapper.HeronVideoVersionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 编辑运行只读视图。槽位表本来就是「一次运行一行」，这里只负责把决策字段翻译成页面能读的形状，
 * 不做任何写入——运行由 {@link NewsAutoService} 产生。
 */
@Service
@RequiredArgsConstructor
public class EditorialRunService {

    private final HeronAutoSlotMapper slotMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final HeronAccessService access;

    public PageResult<EditorialRunVo> queryPageList(EditorialRunQuery query) {
        access.requirePermission("heron:editorial:list");
        // betweenParams 要求两端都给值，单边日期会静默失效；这里两端各自可选
        Object begin = query.getParams() == null ? null : query.getParams().get("beginDate");
        Object end = query.getParams() == null ? null : query.getParams().get("endDate");
        var wrapper = QueryBuilder.lambda(HeronAutoSlot.class)
            .eq(StringUtils.isNotBlank(query.getStatus()), HeronAutoSlot::getStatus, query.getStatus())
            .eq(StringUtils.isNotBlank(query.getSelectedLine()), HeronAutoSlot::getSelectedLine, query.getSelectedLine())
            .ge(begin != null, HeronAutoSlot::getSlotDate, begin)
            .le(end != null, HeronAutoSlot::getSlotDate, end)
            .orderByDesc(HeronAutoSlot::getSlotDate, HeronAutoSlot::getSlotTime)
            .build();
        Page<HeronAutoSlot> page = slotMapper.selectPage(query.build(), wrapper);
        return PageResult.build(enrich(page.getRecords()), page.getTotal());
    }

    public EditorialRunVo queryOne(LocalDate slotDate, String slotTime) {
        access.requirePermission("heron:editorial:query");
        if (slotDate == null || StringUtils.isBlank(slotTime)) {
            throw new HeronBusinessException(400, "slot_required", "槽位日期与时刻不能为空");
        }
        HeronAutoSlot slot = slotMapper.selectOne(QueryBuilder.lambda(HeronAutoSlot.class)
            .eq(HeronAutoSlot::getSlotDate, slotDate)
            .eq(HeronAutoSlot::getSlotTime, slotTime).last("LIMIT 1").build());
        if (slot == null) {
            throw new HeronBusinessException(404, "editorial_run_not_found", "这次运行不存在");
        }
        return enrich(List.of(slot)).get(0);
    }

    /** 批量补作品标题与当前审片状态：一条运行可能没产出（空槽或失败），所以按 workId 存在才查。 */
    private List<EditorialRunVo> enrich(List<HeronAutoSlot> slots) {
        List<EditorialRunVo> rows = MapstructUtils.convert(slots, EditorialRunVo.class);
        List<Long> workIds = rows.stream().map(EditorialRunVo::getWorkId).filter(java.util.Objects::nonNull).distinct().toList();
        if (workIds.isEmpty()) {
            return rows;
        }
        Map<Long, HeronNewsWork> works = workMapper.selectByIds(workIds).stream()
            .collect(Collectors.toMap(HeronNewsWork::getWorkId, Function.identity()));
        List<Long> versionIds = works.values().stream().map(HeronNewsWork::getCurrentVersionId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> reviewStatus = versionIds.isEmpty() ? Map.of()
            : versionMapper.selectByIds(versionIds).stream()
            .collect(Collectors.toMap(HeronVideoVersion::getVersionId, HeronVideoVersion::getReviewStatus));
        for (EditorialRunVo row : rows) {
            HeronNewsWork work = row.getWorkId() == null ? null : works.get(row.getWorkId());
            if (work == null) {
                continue;
            }
            row.setWorkTitle(work.getTitle());
            row.setWorkReviewStatus(work.getCurrentVersionId() == null ? null : reviewStatus.get(work.getCurrentVersionId()));
        }
        return rows;
    }
}
