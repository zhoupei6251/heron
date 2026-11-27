package org.dromara.heron.service.impl.shared.research;

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
import org.dromara.heron.service.impl.shared.support.HeronAccessService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HeronAutoSlotMapper slotMapper;
    private final HeronNewsWorkMapper workMapper;
    private final HeronVideoVersionMapper versionMapper;
    private final HeronAccessService access;

    /**
     * 分页查槽位运行（一槽一行），可按状态、线路与日期范围过滤；日期两端各自可选，
     * 结果补作品标题与当前版本的审片状态后返回。需要权限 {@code heron:editorial:list}。
     *
     * @param query 分页参数与过滤条件；{@code params.beginDate}/{@code params.endDate} 为可选起止日期
     * @return 补齐展示字段后的分页运行视图
     */
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

    /**
     * 按槽位日期与时刻查一次运行，并补作品标题、审片状态与选择/审稿详情。
     * 需要权限 {@code heron:editorial:query}。
     *
     * @throws HeronBusinessException 400 {@code slot_required} 日期或时刻为空；
     *         404 {@code editorial_run_not_found} 该槽位没有运行记录
     */
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
            .collect(Collectors.toMap(HeronNewsWork::getId, Function.identity()));
        List<Long> versionIds = works.values().stream().map(HeronNewsWork::getCurrentVersionId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, HeronVideoVersion> versions = versionIds.isEmpty() ? Map.of()
            : versionMapper.selectByIds(versionIds).stream()
            .collect(Collectors.toMap(HeronVideoVersion::getId, Function.identity()));
        for (EditorialRunVo row : rows) {
            HeronNewsWork work = row.getWorkId() == null ? null : works.get(row.getWorkId());
            if (work == null) {
                continue;
            }
            row.setWorkTitle(work.getTitle());
            HeronVideoVersion version = work.getCurrentVersionId() == null ? null : versions.get(work.getCurrentVersionId());
            if (version == null) {
                continue;
            }
            row.setWorkReviewStatus(version.getReviewStatus());
            row.setSelectionJson(selectionJson(version));
            row.setReviewJson(version.getEditorialReview());
        }
        return rows;
    }

    /**
     * 只取快照里的 selection 节点：正文与证据占整份快照的绝大部分，
     * 编辑策划要看的是「选了哪个候选、哪套模板、什么角度」，不是把它再搬一遍。
     */
    public static String selectionJson(HeronVideoVersion version) {
        if (StringUtils.isBlank(version.getInputSnapshot())) {
            return null;
        }
        // 用本地 mapper 而不是 JsonUtils：这两个字段要在纯单测里可验，不依赖 Spring 上下文
        JsonNode selection = JSON.readTree(version.getInputSnapshot()).path("selection");
        return selection.isObject() ? JSON.writeValueAsString(selection) : null;
    }
}
