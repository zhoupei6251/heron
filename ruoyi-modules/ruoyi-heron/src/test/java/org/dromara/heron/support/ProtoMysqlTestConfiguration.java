package org.dromara.heron.support;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.heron.config.HeronProductionProperties;
import org.dromara.heron.mapper.*;
import org.dromara.heron.service.*;
import org.dromara.heron.service.impl.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Isolated Proto wiring: reuses database infrastructure, never replaces the shared test domain bean. */
@TestConfiguration
@EnableTransactionManagement
@MapperScan("org.dromara.heron.mapper")
@Import({SpringUtils.class, io.github.linpeilie.mapstruct.MapstructAutoConfiguration.class})
public class ProtoMysqlTestConfiguration {
    private final HeronMysqlTestConfiguration infrastructure = new HeronMysqlTestConfiguration();

    @Bean tools.jackson.databind.json.JsonMapper protoJsonMapper() {
        return tools.jackson.databind.json.JsonMapper.builder().build();
    }

    @Bean DataSource dataSource() { return HeronMysqlTestSupport.createDataSource(); }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    @Bean MutableClock testClock() { return new MutableClock(); }
    @Bean MetaObjectHandler metaObjectHandler(MutableClock clock) { return infrastructure.metaObjectHandler(clock); }
    @Bean SqlSessionFactory sqlSessionFactory(DataSource ds, MetaObjectHandler handler) throws Exception {
        return infrastructure.sqlSessionFactory(ds, handler);
    }
    @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean TransactionTemplate transactionTemplate(DataSourceTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
    @Bean HeronAccessService heronAccessService() { return new HeronAccessService(); }
    @Bean ProductionNotificationService notifications(HeronAccessService access) {
        return new ProductionNotificationService(access);
    }
    @Bean HeronProductionProperties properties() {
        var properties = new HeronProductionProperties();
        properties.setDataRoot(Path.of("target", "heron-proto-test-data").toAbsolutePath());
        return properties;
    }
    @Bean ProductionArtifactService artifacts(HeronProductionProperties properties) {
        return new ProductionArtifactService(properties);
    }
    @Bean IProductionService productionService(HeronProductionTaskMapper tasks, HeronProductionLaneMapper lanes,
            HeronTaskStepMapper steps, HeronUsageRecordMapper usage, ApplicationContext context,
            HeronAccessService access, ProductionNotificationService notifications) {
        return new ProductionServiceImpl(tasks, lanes, steps, usage, context, access, notifications);
    }
    @Bean IProductionExecutionService executionService(HeronProductionTaskMapper tasks,
            HeronProductionLaneMapper lanes, HeronTaskStepMapper steps, ApplicationContext context,
            TransactionTemplate tx, MutableClock clock, ProductionArtifactService files,
            ProductionNotificationService notifications) {
        return new ProductionExecutionServiceImpl(tasks, lanes, steps, context, tx, clock, files, notifications);
    }
    @Bean ProtoProductionDomain.CapabilitySnapshot protoCapabilityFixture() {
        return new ProtoProductionDomain.CapabilitySnapshot("a".repeat(40),
            Map.of("blank", "b".repeat(64)), List.of("text", "link"), "transaction-fixture-only");
    }
    @Bean("prototypeProductionPipeline") IProductionPipeline pipeline() {
        return run -> { throw new UnsupportedOperationException("Transaction fixture cannot produce native Axure files"); };
    }
    @Bean("prototypeProductionDomain") ProtoProductionDomain domain(HeronProtoProjectMapper projects,
            HeronProtoVersionMapper versions, ProductionArtifactService files, HeronProductionProperties properties,
            ObjectProvider<ProtoProductionDomain.CapabilitySnapshot> capability,
            @Qualifier("prototypeProductionPipeline") ObjectProvider<IProductionPipeline> pipeline) {
        return new ProtoProductionDomain(projects, versions, files, properties, capability, pipeline);
    }
    @Bean IProtoProjectService protoProjectService(HeronProtoProjectMapper projects, HeronProtoVersionMapper versions,
            HeronProductionTaskMapper tasks, IProductionService production, HeronAccessService access,
            ProtoProductionDomain domain, ProductionArtifactService files, HeronProductionProperties properties,
            MutableClock clock) {
        return new ProtoProjectServiceImpl(projects, versions, tasks, production, access, domain, files,
            properties, clock, ProtoMysqlTestSupport.TARGET);
    }
}
