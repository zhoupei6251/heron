package org.dromara.heron.support;

import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import org.dromara.heron.service.IProductionPipeline;
import org.dromara.heron.service.IProductionRecoveryPolicy;

import java.util.List;
import java.util.function.Function;

/**
 * 测试流水线：行为按用例注入，不代表真实生成能力。
 */
public class TestProductionPipeline implements IProductionPipeline, IProductionRecoveryPolicy {

    private volatile Function<ProductionRunVo, ProductionResultVo> action =
        run -> new ProductionResultVo(List.of(), "{\"result\":true}");
    private volatile boolean resume;

    public void setAction(Function<ProductionRunVo, ProductionResultVo> action) {
        this.action = action;
    }

    public void setResume(boolean resume) {
        this.resume = resume;
    }

    public void reset() {
        action = run -> new ProductionResultVo(List.of(), "{\"result\":true}");
        resume = false;
    }

    @Override
    public ProductionResultVo execute(ProductionRunVo run) {
        return action.apply(run);
    }

    @Override
    public boolean canResume(ProductionRunVo run) {
        return resume;
    }
}
