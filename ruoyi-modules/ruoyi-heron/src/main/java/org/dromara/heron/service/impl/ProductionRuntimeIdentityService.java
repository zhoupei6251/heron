package org.dromara.heron.service.impl;

import org.dromara.heron.domain.HeronTaskStep;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.mapper.HeronTaskStepMapper;
import org.springframework.stereotype.Service;

/**
 * 把监护脚本写出的启动身份落到步骤，恢复时只按该身份核验，不按旧PID猜测。
 */
@Service
public class ProductionRuntimeIdentityService {

    private static final int MAX_IDENTITY_LENGTH = 4096;

    private final HeronTaskStepMapper stepMapper;

    public ProductionRuntimeIdentityService(HeronTaskStepMapper stepMapper) {
        this.stepMapper = stepMapper;
    }

    public boolean record(ProductionRunVo run, String identityJson) {
        if (identityJson == null || identityJson.isBlank() || identityJson.length() > MAX_IDENTITY_LENGTH) {
            return false;
        }
        return stepMapper.lambda()
            .set(HeronTaskStep::getRuntimeIdentity, identityJson)
            .eq(HeronTaskStep::getTaskId, run.taskId())
            .eq(HeronTaskStep::getAttemptNo, run.attemptNo())
            .eq(HeronTaskStep::getStatus, ProductionStatus.running.name())
            .exists("SELECT 1 FROM heron_production_task WHERE task_id = {0} AND run_token = {1} "
                + "AND attempt_no = {2} AND status = 'running'",
                run.taskId(), run.runToken(), run.attemptNo())
            .updateCount() > 0;
    }

    public String latest(long taskId, int attemptNo) {
        HeronTaskStep step = stepMapper.selectLatestIdentity(taskId, attemptNo);
        return step == null ? null : step.getRuntimeIdentity();
    }
}
