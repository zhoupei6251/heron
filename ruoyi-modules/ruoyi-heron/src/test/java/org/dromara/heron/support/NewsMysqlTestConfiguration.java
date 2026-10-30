package org.dromara.heron.support;

import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.service.impl.NewsProductionDomain;
import org.dromara.heron.service.impl.NewsWorkServiceImpl;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** N1 独立装配：复用真实 MySQL 设施，按名字替换测试域，不用 @Primary 掩盖同名域。 */
@TestConfiguration
@org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
@Import({HeronMysqlTestConfiguration.class, NewsWorkServiceImpl.class, SpringUtils.class,
    io.github.linpeilie.mapstruct.MapstructAutoConfiguration.class})
public class NewsMysqlTestConfiguration {
    @Bean
    static BeanFactoryPostProcessor newsDomainBinding() {
        return factory -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
            registry.removeBeanDefinition("newsProductionDomain");
            registry.removeBeanDefinition("newsProductionPipeline");
            RootBeanDefinition definition = new RootBeanDefinition(NewsProductionDomain.class);
            definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            registry.registerBeanDefinition("newsProductionDomain", definition);
        };
    }

    @Bean
    JsonMapper newsJsonMapper() {
        return JsonMapper.builder().build();
    }
}
