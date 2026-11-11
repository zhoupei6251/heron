package org.dromara.heron.service.impl;

import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.heron.domain.HeronNewsCandidate;
import org.dromara.heron.domain.HeronNewsSource;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.HeronUsageRecord;
import org.dromara.heron.domain.ProductionBizType;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.vo.HeronHealthVo;
import org.dromara.heron.mapper.HeronNewsCandidateMapper;
import org.dromara.heron.mapper.HeronNewsSourceMapper;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronUsageRecordMapper;
import org.dromara.heron.service.IHeronHealthService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 健康概览：把「今天有没有正常产出」压成一行可判断的数字。
 * 只读聚合，不新增表、不写缓存——数据量是日更三条的量级。
 */
@Service
public class HeronHealthServiceImpl implements IHeronHealthService {
    private static final String ENABLED = "0";

    private final HeronNewsSourceMapper sourceMapper;
    private final HeronNewsCandidateMapper candidateMapper;
    private final HeronProductionTaskMapper taskMapper;
    private final HeronUsageRecordMapper usageMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public HeronHealthServiceImpl(HeronNewsSourceMapper sourceMapper, HeronNewsCandidateMapper candidateMapper,
                                  HeronProductionTaskMapper taskMapper, HeronUsageRecordMapper usageMapper) {
        this(sourceMapper, candidateMapper, taskMapper, usageMapper, Clock.systemUTC());
    }

    public HeronHealthServiceImpl(HeronNewsSourceMapper sourceMapper, HeronNewsCandidateMapper candidateMapper,
                                  HeronProductionTaskMapper taskMapper, HeronUsageRecordMapper usageMapper,
                                  Clock clock) {
        this.sourceMapper = sourceMapper;
        this.candidateMapper = candidateMapper;
        this.taskMapper = taskMapper;
        this.usageMapper = usageMapper;
        this.clock = clock;
    }

    @Override
    public HeronHealthVo overview() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime monthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();

        HeronHealthVo vo = new HeronHealthVo();
        vo.setGeneratedAt(now);
        fillSources(vo);

        vo.setTodayCandidates(candidateMapper.selectCount(
            QueryBuilder.lambda(HeronNewsCandidate.class).ge(HeronNewsCandidate::getFirstSeenAt, dayStart).build()));
        vo.setTodayTasks(taskMapper.selectCount(QueryBuilder.lambda(HeronProductionTask.class)
            .ge(HeronProductionTask::getCreateTime, dayStart)
            .eq(HeronProductionTask::getBizType, ProductionBizType.news_video.name()).build()));
        vo.setTodayFailed(taskMapper.selectCount(QueryBuilder.lambda(HeronProductionTask.class)
            .ge(HeronProductionTask::getCreateTime, dayStart)
            .eq(HeronProductionTask::getBizType, ProductionBizType.news_video.name())
            .eq(HeronProductionTask::getStatus, ProductionStatus.failed.name()).build()));

        fillUsage(vo, monthStart);
        return vo;
    }

    private void fillSources(HeronHealthVo vo) {
        List<HeronNewsSource> sources = sourceMapper.selectList();
        vo.setSourceTotal(sources.size());
        vo.setSourceEnabled((int) sources.stream().filter(s -> ENABLED.equals(s.getEnabled())).count());
        vo.setSourceFailing((int) sources.stream().filter(s -> hasError(s.getErrorReason())).count());

        sources.stream()
            .filter(s -> s.getLastSuccessAt() != null)
            .max(Comparator.comparing(HeronNewsSource::getLastSuccessAt))
            .ifPresent(s -> {
                vo.setLastSuccessSource(s.getName());
                vo.setLastSuccessAt(s.getLastSuccessAt());
            });

        Optional<HeronNewsSource> failed = sources.stream()
            .filter(s -> hasError(s.getErrorReason()))
            .max(Comparator.comparing(s -> Optional.ofNullable(s.getLastAttemptAt()).orElse(LocalDateTime.MIN)));
        failed.ifPresent(s -> {
            vo.setLastErrorSource(s.getName());
            vo.setLastErrorReason(s.getErrorReason());
        });
    }

    private static boolean hasError(String reason) {
        return reason != null && !reason.isBlank();
    }

    private void fillUsage(HeronHealthVo vo, LocalDateTime monthStart) {
        List<HeronUsageRecord> records = usageMapper.selectList(
            QueryBuilder.lambda(HeronUsageRecord.class).ge(HeronUsageRecord::getCreateTime, monthStart).build());
        Set<Long> tasks = new HashSet<>();
        long input = 0;
        long output = 0;
        for (HeronUsageRecord record : records) {
            if (record.getTaskId() != null) {
                tasks.add(record.getTaskId());
            }
            input += record.getInputTokens() == null ? 0 : record.getInputTokens();
            output += record.getOutputTokens() == null ? 0 : record.getOutputTokens();
        }
        vo.setMonthTasks(tasks.size());
        vo.setMonthInputTokens(input);
        vo.setMonthOutputTokens(output);
    }
}
