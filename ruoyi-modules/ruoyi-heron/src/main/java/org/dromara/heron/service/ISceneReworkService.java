package org.dromara.heron.service;

import org.dromara.heron.domain.vo.ProductionResultVo;
import org.dromara.heron.domain.vo.ProductionRunVo;
import tools.jackson.databind.JsonNode;

/** 数据库权威版本上的场景返工入口；调用方不能提交上一版 ID、manifest 或 script 快照。 */
public interface ISceneReworkService {
    ProductionResultVo rework(ProductionRunVo run, String sceneId, JsonNode replacementScene);
}
