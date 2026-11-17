package org.dromara.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 数据库迁移：启动时把 {@code db/migration} 下未应用的脚本按序执行。
 *
 * <p>为什么不用 Spring Boot 的 Flyway 自动配置：本项目用 dynamic-datasource，
 * 它的 {@code DynamicRoutingDataSource} 注册时机晚于自动配置的条件求值，
 * 于是自动配置被静默跳过——依赖在 jar 里、配置也读了，但一条迁移都不执行，
 * 这种「看着配好了其实没跑」比直接报错更危险。这里显式建 bean，顺序自己掌握。
 *
 * <p>注入的 DataSource 是 dynamic 的入口，它会路由到 {@code primary: master}，
 * 所以单库部署不需要为迁移单独指数据源。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    /**
     * {@code initMethod = "migrate"} 让迁移在容器启动时执行完，
     * 保证业务 bean 拿到连接时库结构已经是最新的。
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource,
                         @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
                         @Value("${spring.flyway.table:flyway_schema_history}") String table,
                         @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate,
                         @Value("${spring.flyway.baseline-version:45}") String baselineVersion,
                         @Value("${spring.flyway.baseline-description:baseline}") String baselineDescription,
                         @Value("${spring.flyway.validate-on-migrate:true}") boolean validateOnMigrate) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(locations.split(","))
            .table(table)
            .baselineOnMigrate(baselineOnMigrate)
            .baselineVersion(baselineVersion)
            .baselineDescription(baselineDescription)
            .validateOnMigrate(validateOnMigrate)
            .load();
    }
}
