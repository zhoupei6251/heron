package org.dromara.heron.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.heron.domain.vo.VideoTemplateVo;
import org.dromara.heron.service.impl.TemplateCatalogService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 视频模板目录：只读。模板版本由 LOCK.json 与渲染闸决定，不由后台编辑。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/heron/template")
public class TemplateController extends BaseController {

    private final TemplateCatalogService catalog;

    @GetMapping("/list")
    public R<List<VideoTemplateVo>> list() {
        return R.ok(catalog.list());
    }
}
