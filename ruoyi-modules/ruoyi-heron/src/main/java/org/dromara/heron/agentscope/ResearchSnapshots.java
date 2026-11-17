package org.dromara.heron.agentscope;

import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.heron.domain.HeronResearchSnapshot;
import org.dromara.heron.mapper.HeronResearchSnapshotMapper;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次研究 attempt 沿途真正取到的数据快照。
 *
 * <p>为什么要在研究期落库：工具里调的是 {@code DatasetFetchService.snapshot()}，它不落产物，
 * 而模型的结论正是基于这份数据。不冻结的话，「这条结论基于哪一版数据」事后只能靠时间猜，
 * 源一变就再也核不出来。
 *
 * <p>同一 (attempt, 源) 只留第一次：一次 attempt 里对同一源取两次若版本已经不同，
 * 那本身就说明这轮结论可疑，留第一份足够定位问题。
 *
 * <p>落库失败只记日志：证据链会如实标成未绑定，不会假装绑上了。
 */
@Slf4j
public final class ResearchSnapshots {

    /** 一条被冻结的快照：证据链只需要这三样，原文留在库里。 */
    public record Ref(String sourceKey, String dataSha256, String retrievedAt) {
    }

    private final HeronResearchSnapshotMapper mapper;
    private final long researchId;
    private final int attemptNo;
    private final Map<String, Ref> recorded = new LinkedHashMap<>();

    public ResearchSnapshots(HeronResearchSnapshotMapper mapper, long researchId, int attemptNo) {
        this.mapper = mapper;
        this.researchId = researchId;
        this.attemptNo = attemptNo;
    }

    /** 不落库的版本：单测与冒烟只要「有这层接线」而不想要一张表。 */
    public static ResearchSnapshots disabled() {
        return new ResearchSnapshots(null, 0, 0);
    }

    public void record(String sourceKey, JsonNode snapshot) {
        if (sourceKey == null || sourceKey.isBlank() || snapshot == null || recorded.containsKey(sourceKey)) {
            return;
        }
        Ref ref = new Ref(sourceKey,
            blankToNull(snapshot.path("data_sha256").asString("")),
            blankToNull(snapshot.path("retrieved_at").asString("")));
        recorded.put(sourceKey, ref);
        if (mapper == null) {
            return;
        }
        HeronResearchSnapshot row = new HeronResearchSnapshot();
        row.setSnapshotId(IdUtil.getSnowflakeNextId());
        row.setResearchId(researchId);
        row.setAttemptNo(attemptNo);
        row.setSourceKey(sourceKey);
        row.setDataSha256(ref.dataSha256());
        row.setRetrievedAt(ref.retrievedAt());
        row.setContract(blankToNull(snapshot.path("contract").asString("")));
        JsonNode rows = snapshot.path("rows");
        row.setPoints(rows.isArray() ? rows.size() : null);
        row.setSnapshotJson(snapshot.toString());
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(row.getCreateTime());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 同一 attempt 同一源已经落过：重试或并行工具调用，第一份就是要留的那份
        } catch (RuntimeException e) {
            log.warn("研究快照落库失败 researchId={} attempt={} source={} cause={}",
                researchId, attemptNo, sourceKey, e.getMessage());
        }
    }

    /** 本次 attempt 已冻结的快照，按源索引；证据链据此把结论指回具体数据版本。 */
    public Map<String, Ref> recorded() {
        return Map.copyOf(recorded);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
