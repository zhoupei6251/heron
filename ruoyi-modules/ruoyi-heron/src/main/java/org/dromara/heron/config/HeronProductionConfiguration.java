package org.dromara.heron.config;

import cn.hutool.crypto.digest.DigestUtil;
import org.dromara.common.core.factory.YmlPropertySourceFactory;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.NewsScriptAuthor;
import org.dromara.heron.service.ProtoRp1Author;
import org.dromara.heron.service.impl.NewsProductionPipeline;
import org.dromara.heron.service.impl.OpenAiModelClient;
import org.dromara.heron.service.impl.ProtoProductionDomain;
import org.dromara.heron.service.impl.ProtoProductionPipeline;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.dromara.heron.service.impl.ProductionRecoveryService;
import org.dromara.heron.service.impl.ProductionRunner;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 模块内配置入口，不改动基座 application 配置。
 */
@Configuration
@EnableConfigurationProperties(HeronProductionProperties.class)
@PropertySource(value = "classpath:heron-production.yml", factory = YmlPropertySourceFactory.class)
public class HeronProductionConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "heron.production", name = "worker-enabled", havingValue = "true")
    public ProductionRunner productionRunner(ApplicationContext pipelines,
                                             IProductionExecutionService execution,
                                             ProductionProcessService processes,
                                             ProductionRecoveryService recovery,
                                             ProductionUsageService usage,
                                             @Qualifier("scheduledExecutorService")
                                             ScheduledExecutorService scheduler) {
        return new ProductionRunner(pipelines, execution, processes, recovery, usage, scheduler);
    }

    /**
     * 装配流水线需要 RP/1 作者 bean 同时存在；只开开关不配模型时启动即失败，不留到运行期才报 503。
     */
    @Bean("prototypeProductionPipeline")
    @ConditionalOnProperty(prefix = "heron.prototype", name = "engine-enabled", havingValue = "true")
    public ProtoProductionPipeline prototypeProductionPipeline(ProtoRp1Author author,
                                                              ProductionProcessService processes,
                                                              ProductionArtifactService artifacts,
                                                              HeronProductionProperties properties,
                                                              IProductionExecutionService execution) {
        return new ProtoProductionPipeline(author, processes, artifacts, properties, execution::beginStage);
    }

    /**
     * 新闻流水线同理：bean 名由 ProductionRunner 与 ProductionRecoveryService 双向硬编码，改名等于没注册。
     */
    @Bean("newsProductionPipeline")
    @ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
    public NewsProductionPipeline newsProductionPipeline(NewsScriptAuthor author,
                                                         ProductionProcessService processes,
                                                         ProductionArtifactService artifacts,
                                                         HeronProductionProperties properties,
                                                         IProductionExecutionService execution) {
        return new NewsProductionPipeline(author, processes, artifacts, properties, execution::beginStage);
    }

    /**
     * 原型线的部署能力声明（引擎 commit、受支持控件）必须由人核对后写进配置；但"声明过"不等于"就是它"，
     * 这里仍拿配置去比引擎目录的实际 HEAD 与空白模板的实算哈希 —— 不凭路径存在就给出能力。
     * 配置缺失时不注册本 bean，reserve() 保持 503 fail-closed。
     */
    @Bean
    @ConditionalOnProperty(prefix = "heron.prototype", name = "engine-assets")
    public ProtoProductionDomain.CapabilitySnapshot prototypeCapabilitySnapshot(HeronProductionProperties properties,
                                                                               Environment environment,
                                                                               OpenAiModelClient model) {
        JsonNode declared = readJson(environment.getProperty("heron.prototype.engine-assets"));
        String commit = declared.path("engineCommit").asString("");
        if (!commit.matches("[0-9a-f]{40,64}")) {
            throw new IllegalStateException("heron.prototype.engine-assets 缺少合法的引擎 commit");
        }
        Path head = Path.of(properties.getZeeRp()).resolve(".git").resolve("HEAD");
        String actual = headFile(head);
        if (!commit.equals(actual)) {
            throw new IllegalStateException("引擎实际提交 " + actual + " 与声明的 " + commit + " 不一致");
        }
        List<String> widgets = new ArrayList<>();
        declared.path("supportedWidgets").forEach(item -> widgets.add(item.asString("")));
        if (widgets.isEmpty()) {
            throw new IllegalStateException("引擎能力声明缺少受支持控件清单");
        }
        Path template = properties.getPrototypeTemplate();
        if (template == null || !Files.isRegularFile(template)) {
            throw new IllegalStateException("没有 Axure RP9 空白模板，无法给出原型能力");
        }
        String manifestKey = template.getFileName().toString().replace('.', '_');
        Map<String, String> manifest = Map.of(manifestKey, DigestUtil.sha256Hex(template.toFile()));
        String modelName = model.modelName();
        if (modelName == null) {
            throw new IllegalStateException("声明了引擎能力却没配 heron.model.name，产物无法标注模型档位");
        }
        return new ProtoProductionDomain.CapabilitySnapshot(commit, manifest, List.copyOf(widgets), modelName);
    }

    private static JsonNode readJson(String text) {
        try {
            return JsonMapper.builder().build().readTree(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("heron.prototype.engine-assets 不是合法 JSON", e);
        }
    }

    /** 解包签出是 detached HEAD（一行 sha），分支签出是 ref 指针，两种都要还原成提交号。 */
    private static String headFile(Path head) {
        try {
            String content = Files.readString(head).trim();
            if (!content.startsWith("ref:")) {
                return content;
            }
            return Files.readString(Path.of(head.getParent().getParent().toString(),
                content.substring(4).trim())).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
