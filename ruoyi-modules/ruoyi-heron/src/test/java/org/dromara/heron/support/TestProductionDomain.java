package org.dromara.heron.support;

import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.HeronBusinessException;
import org.dromara.heron.domain.HeronProductionTask;
import org.dromara.heron.domain.bo.ProductionSubmitBo;
import org.dromara.heron.domain.vo.ProductionReservationVo;
import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.service.IProductionDomain;
import org.dromara.heron.service.IProductionObjectScope;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 测试业务线：版本表与对象锚点都在隔离库内，行为可注入失败。
 */
public class TestProductionDomain implements IProductionDomain, IProductionObjectScope {

    private final JdbcTemplate jdbc;
    private volatile boolean failOnComplete;
    private volatile String lastIsolation;
    private volatile String lastLockIsolation;
    private volatile long failLockObjectId = Long.MIN_VALUE;

    public TestProductionDomain(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setFailOnComplete(boolean failOnComplete) {
        this.failOnComplete = failOnComplete;
    }

    public void setFailLockObjectId(long bizId) {
        this.failLockObjectId = bizId;
    }

    public String lastIsolation() {
        return lastIsolation;
    }

    public String lastLockIsolation() {
        return lastLockIsolation;
    }

    @Override
    public long ownerOf(long bizId) {
        try {
            return jdbc.queryForObject(
                "SELECT owner_id FROM heron_test_object WHERE object_id=? FOR UPDATE",
                Long.class, bizId);
        } catch (EmptyResultDataAccessException e) {
            throw new HeronAccessException();
        }
    }

    @Override
    public void lockObject(long bizId) {
        lastLockIsolation = jdbc.queryForObject("SELECT @@transaction_isolation", String.class);
        if (bizId == failLockObjectId) {
            throw new IllegalStateException("测试注入：锁定对象失败");
        }
        try {
            jdbc.queryForObject(
                "SELECT object_id FROM heron_test_object WHERE object_id=? FOR UPDATE",
                Long.class, bizId);
        } catch (EmptyResultDataAccessException e) {
            throw new HeronAccessException();
        }
    }

    @Override
    public ProductionReservationVo reserve(ProductionSubmitBo command, long actorId, long taskId) {
        lastIsolation = jdbc.queryForObject("SELECT @@transaction_isolation", String.class);
        Map<String, Object> object = selectObject(command.bizId());
        if (!command.bizType().name().equals(object.get("biz_type"))) {
            throw new HeronAccessException();
        }
        long revision = ((Number) object.get("revision")).longValue();
        if (command.expectedRevision() != revision) {
            throw new HeronBusinessException(409, "production_revision_conflict", "对象版本已变化");
        }
        if (object.get("active_task") != null) {
            throw new HeronBusinessException(409, "production_object_busy", "对象已有在途任务");
        }
        long roleOwner = ((Number) object.get("owner_id")).longValue();
        long versionNo = ((Number) object.get("next_version")).longValue();
        String inputJson = "{\"bizId\":" + command.bizId()
            + ",\"revision\":" + command.expectedRevision()
            + ",\"requestKey\":\"" + command.requestKey() + "\"}";
        jdbc.update("""
                INSERT INTO heron_test_version
                  (version_id,object_id,version_no,content,task_id,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(3))
                """,
            taskId, command.bizId(), versionNo, inputJson, taskId);
        jdbc.update("""
                UPDATE heron_test_object
                SET revision=?,next_version=?,active_task=?
                WHERE object_id=?
                """,
            versionNo, versionNo + 1, taskId, command.bizId());
        return new ProductionReservationVo(taskId, roleOwner, inputJson);
    }

    @Override
    public void complete(HeronProductionTask task, ProductionResultVo result) {
        jdbc.update("UPDATE heron_test_version SET result_json=? WHERE version_id=?",
            result.resultJson(), task.getVersionId());
        jdbc.update("""
                UPDATE heron_test_object
                SET current_version_id=?,active_task=NULL
                WHERE object_id=? AND active_task=?
                """,
            task.getVersionId(), task.getBizId(), task.getTaskId());
        if (failOnComplete) {
            throw new IllegalStateException("测试注入：完成阶段失败");
        }
    }

    @Override
    public void release(HeronProductionTask task) {
        jdbc.update("UPDATE heron_test_object SET active_task=NULL WHERE object_id=? AND active_task=?",
            task.getBizId(), task.getTaskId());
    }

    private Map<String, Object> selectObject(long bizId) {
        try {
            return jdbc.queryForMap("""
                SELECT object_id,biz_type,owner_id,revision,next_version,active_task
                FROM heron_test_object WHERE object_id=? FOR UPDATE
                """, bizId);
        } catch (EmptyResultDataAccessException e) {
            throw new HeronAccessException();
        }
    }
}
