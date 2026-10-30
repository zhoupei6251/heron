package org.dromara.heron.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.ProductionStatus;
import org.dromara.heron.domain.bo.ProductionQueryBo;
import org.dromara.heron.domain.vo.ProductionCapabilitiesVo;
import org.dromara.heron.domain.vo.ProductionFileVo;
import org.dromara.heron.domain.vo.ProductionStepVo;
import org.dromara.heron.domain.vo.ProductionTaskVo;
import org.dromara.heron.service.IProductionService;
import org.dromara.heron.service.impl.HeronAccessService;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionCapabilityService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 任务中心：列表走按钮权限，按 ID 的查询与文件先做对象判定。
 */
@RestController
@RequestMapping("/heron/production")
public class ProductionController {

    private final IProductionService productionService;
    private final ProductionCapabilityService capabilities;
    private final ProductionArtifactService artifacts;
    private final HeronAccessService access;

    public ProductionController(IProductionService productionService,
                                ProductionCapabilityService capabilities,
                                ProductionArtifactService artifacts,
                                HeronAccessService access) {
        this.productionService = productionService;
        this.capabilities = capabilities;
        this.artifacts = artifacts;
        this.access = access;
    }

    @SaCheckPermission("heron:production:list")
    @GetMapping("/list")
    public R<PageResult<ProductionTaskVo>> list(ProductionQueryBo query, PageQuery pageQuery) {
        return R.ok(productionService.queryPageList(query, pageQuery));
    }

    @GetMapping("/capabilities")
    public R<ProductionCapabilitiesVo> capabilities() {
        access.requireAnyPermission("heron:newsWork:query", "heron:proto:query");
        return R.ok(capabilities.getCapabilities());
    }

    @GetMapping("/{taskId}")
    public R<ProductionTaskVo> getInfo(@PathVariable long taskId) {
        return R.ok(productionService.queryById(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public R<ProductionTaskVo> cancel(@PathVariable long taskId) {
        return R.ok(productionService.cancel(taskId));
    }

    @GetMapping("/{taskId}/evidence/{artifactId}")
    public ResponseEntity<InputStreamResource> evidence(@PathVariable long taskId,
                                                        @PathVariable String artifactId) {
        ProductionTaskVo task = productionService.queryById(taskId);
        ProductionFileVo file = evidenceOf(task, artifactId);
        ProductionArtifactService.EvidenceStream stream = artifacts.openEvidence(taskId, task.getBizType(),
            file.artifactId(), file.bytes(), file.sha256(), file.mediaType());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(stream.mediaType()))
            .contentLength(stream.bytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(stream.fileName(), StandardCharsets.UTF_8).build().toString())
            .body(new InputStreamResource(stream.content()));
    }

    private static ProductionFileVo evidenceOf(ProductionTaskVo task, String artifactId) {
        List<ProductionStepVo> steps = task.getSteps();
        if (steps != null) {
            for (ProductionStepVo step : steps) {
                if (!ProductionStatus.failed.name().equals(step.getStatus()) || step.getEvidence() == null) {
                    continue;
                }
                for (ProductionFileVo file : step.getEvidence()) {
                    if (file.artifactId().equals(artifactId)) {
                        return file;
                    }
                }
            }
        }
        throw new HeronAccessException();
    }
}
