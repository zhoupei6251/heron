package org.dromara.heron.support;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.dromara.heron.mapper.HeronProductionLaneMapper;
import org.dromara.heron.mapper.HeronProductionTaskMapper;
import org.dromara.heron.mapper.HeronTaskStepMapper;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.mapper.HeronUsageRecordMapper;
import org.dromara.heron.service.IProductionExecutionService;
import org.dromara.heron.service.IProductionService;
import org.dromara.heron.service.impl.HeronAccessService;
import org.dromara.heron.service.impl.ProductionArtifactService;
import org.dromara.heron.service.impl.ProductionCapabilityService;
import org.dromara.heron.service.impl.ProductionCleanupService;
import org.dromara.heron.service.impl.ProductionExecutionServiceImpl;
import org.dromara.heron.service.impl.ProductionNotificationService;
import org.dromara.heron.service.impl.ProductionProcessService;
import org.dromara.heron.service.impl.ProductionRecoveryService;
import org.dromara.heron.service.impl.ProductionRuntimeIdentityService;
import org.dromara.heron.service.impl.ProductionServiceImpl;
import org.dromara.heron.service.impl.ProductionUsageService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;

@TestConfiguration
@EnableTransactionManagement
@MapperScan("org.dromara.heron.mapper")
public class HeronMysqlTestConfiguration {

    @Bean
    public DataSource dataSource() {
        return HeronMysqlTestSupport.createDataSource();
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    public MetaObjectHandler metaObjectHandler(MutableClock clock) {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now(clock);
                setFieldValByName("createTime", now, metaObject);
                setFieldValByName("updateTime", now, metaObject);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                setFieldValByName("updateTime", LocalDateTime.now(clock), metaObject);
            }
        };
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               MetaObjectHandler metaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig()
            .setDbConfig(new GlobalConfig.DbConfig())
            .setMetaObjectHandler(metaObjectHandler));
        MybatisPlusInterceptor plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factory.setPlugins(plugins);
        return factory.getObject();
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(DataSourceTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public HeronAccessService heronAccessService() {
        return new HeronAccessService();
    }

    @Bean
    public IProductionService productionService(HeronProductionTaskMapper taskMapper,
                                                HeronProductionLaneMapper laneMapper,
                                                HeronTaskStepMapper stepMapper,
                                                HeronUsageRecordMapper usageMapper,
                                                ApplicationContext context,
                                                HeronAccessService access,
                                                ProductionNotificationService notifications) {
        return new ProductionServiceImpl(taskMapper, laneMapper, stepMapper, usageMapper, context, access,
            notifications);
    }

    @Bean
    public IProductionExecutionService productionExecutionService(HeronProductionTaskMapper taskMapper,
                                                                   HeronProductionLaneMapper laneMapper,
                                                                   HeronTaskStepMapper stepMapper,
                                                                   ApplicationContext context,
                                                                   TransactionTemplate transactionTemplate,
                                                                   MutableClock clock,
                                                                   ProductionArtifactService artifacts,
                                                                   ProductionNotificationService notifications) {
        return new ProductionExecutionServiceImpl(taskMapper, laneMapper, stepMapper, context,
            transactionTemplate, clock, artifacts, notifications);
    }

    @Bean
    public ProductionNotificationService productionNotificationService(HeronAccessService access) {
        return new ProductionNotificationService(access);
    }

    @Bean("newsProductionDomain")
    public TestProductionDomain newsProductionDomain(JdbcTemplate jdbc) {
        return new TestProductionDomain(jdbc);
    }

    @Bean("prototypeProductionDomain")
    public TestProductionDomain prototypeProductionDomain(JdbcTemplate jdbc) {
        return new TestProductionDomain(jdbc);
    }

    @Bean
    public HeronProductionProperties heronProductionProperties() {
        HeronProductionProperties properties = new HeronProductionProperties();
        properties.setDataRoot(java.nio.file.Path.of("target", "heron-test-data").toAbsolutePath());
        properties.setWorkerEnabled(true);
        return properties;
    }

    @Bean
    public ProductionArtifactService productionArtifactService(HeronProductionProperties properties) {
        return new ProductionArtifactService(properties);
    }

    @Bean
    public ProductionCapabilityService productionCapabilityService(ApplicationContext context,
                                                                   HeronProductionProperties properties) {
        return new ProductionCapabilityService(context, properties);
    }

    @Bean
    public ProductionRuntimeIdentityService productionRuntimeIdentityService(HeronTaskStepMapper stepMapper) {
        return new ProductionRuntimeIdentityService(stepMapper);
    }

    @Bean
    public ProductionProcessService productionProcessService(HeronProductionProperties properties,
                                                             ProductionArtifactService artifacts,
                                                             ProductionRuntimeIdentityService identities) {
        return new ProductionProcessService(properties, artifacts, identities);
    }

    @Bean
    public ProductionUsageService productionUsageService(HeronUsageRecordMapper usageMapper) {
        return new ProductionUsageService(usageMapper);
    }

    @Bean
    public ProductionRecoveryService productionRecoveryService(HeronProductionTaskMapper taskMapper,
                                                               IProductionExecutionService execution,
                                                               ProductionProcessService processes,
                                                               ProductionUsageService usage,
                                                               ProductionRuntimeIdentityService identities,
                                                               ApplicationContext context,
                                                               MutableClock clock) {
        return new ProductionRecoveryService(taskMapper, execution, processes, usage, identities, context, clock);
    }

    @Bean
    public ProductionCleanupService productionCleanupService(HeronProductionTaskMapper taskMapper,
                                                             HeronProductionProperties properties,
                                                             MutableClock clock) {
        return new ProductionCleanupService(taskMapper, properties, clock);
    }

    @Bean("newsProductionPipeline")
    public TestProductionPipeline newsProductionPipeline() {
        return new TestProductionPipeline();
    }
}
