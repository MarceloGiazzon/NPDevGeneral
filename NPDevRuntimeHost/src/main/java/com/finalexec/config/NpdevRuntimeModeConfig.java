package com.finalexec.config;

import com.npdev.adapters.audit.inproc.InProcAuditLogStore;
import com.npdev.adapters.audit.jdbc.JdbcAuditLogStore;
import com.npdev.adapters.bulkhead.inproc.InProcBulkheadStore;
import com.npdev.adapters.bulkhead.postgres.PostgresAdvisoryBulkheadStore;
import com.npdev.adapters.circuit.inproc.InProcCircuitBreakerStateStore;
import com.npdev.adapters.circuit.jdbc.JdbcCircuitBreakerStateStore;
import com.npdev.adapters.eventstore.jdbc.JdbcEventStore;
import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.adapters.flowinstance.inproc.InProcCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.adapters.flowinstance.jdbc.JdbcCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.jdbc.JdbcFlowInstanceStore;
import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.adapters.idempotency.jdbc.JdbcIdempotencyStore;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.adapters.tracestore.PersistentExecutionTracer;
import com.npdev.adapters.tracestore.jdbc.JdbcTraceStore;
import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.finalexec.db.JdbcBusinessConceptStore;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class NpdevRuntimeModeConfig {

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public EventStore inProcEventStore() {
        return new InProcEventStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public EventStore jdbcEventStore(DataSource dataSource) {
        return new JdbcEventStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public InProcExecutionTracer inProcExecutionTracer() {
        return new InProcExecutionTracer();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public FlowInstanceStore inProcFlowInstanceStore() {
        return new InProcFlowInstanceStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public CorrelationOwnershipStore inProcCorrelationOwnershipStore() {
        return new InProcCorrelationOwnershipStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public FlowInstanceStore jdbcFlowInstanceStore(DataSource dataSource) {
        return new JdbcFlowInstanceStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public CorrelationOwnershipStore jdbcCorrelationOwnershipStore(DataSource dataSource) {
        return new JdbcCorrelationOwnershipStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public TraceStore jdbcTraceStore(DataSource dataSource) {
        return new JdbcTraceStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public AuditLogStore inProcAuditLogStore() {
        return new InProcAuditLogStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public AuditLogStore jdbcAuditLogStore(DataSource dataSource) {
        return new JdbcAuditLogStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public CircuitBreakerStateStore inProcCircuitBreakerStateStore() {
        return new InProcCircuitBreakerStateStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public CircuitBreakerStateStore jdbcCircuitBreakerStateStore(DataSource dataSource) {
        return new JdbcCircuitBreakerStateStore(dataSource);
    }

    @Bean
    public BulkheadStore bulkheadStore(
            @Value("${npdev.database.engine:InMemory}") String engine,
            @Value("${npdev.storage.mode:in-memory}") String storageMode,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        if ("jdbc".equalsIgnoreCase(storageMode) && "Postgres".equalsIgnoreCase(engine)) {
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException("DataSource is required for Postgres bulkhead store.");
            }
            return new PostgresAdvisoryBulkheadStore(dataSource);
        }
        return new InProcBulkheadStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public IdempotencyStore inProcIdempotencyStore() {
        return new InProcIdempotencyStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public IdempotencyStore jdbcIdempotencyStore(DataSource dataSource) {
        return new JdbcIdempotencyStore(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public ExecutionTracer jdbcExecutionTracer(TraceStore traceStore) {
        return new PersistentExecutionTracer(traceStore);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "in-memory", matchIfMissing = true)
    public ConceptStore inMemoryConceptStore() {
        return new InMemoryConceptStore();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.storage.mode", havingValue = "jdbc")
    public ConceptStore jdbcConceptStore(DataSource dataSource, CompiledModel compiledModel) {
        return new JdbcBusinessConceptStore(dataSource, compiledModel);
    }

    @Bean
    public RuntimeSettings runtimeSettings(
            @Value("${npdev.runtime.mode:inproc}") String mode,
            @Value("${npdev.scheduler.enabled:true}") boolean schedulerEnabled,
            @Value("${npdev.scheduler.batch-limit:${npdev.resume.limit:1000}}") int schedulerBatchLimit,
            @Value("${npdev.scheduler.tick-millis:${npdev.resume.pollMs:2000}}") int schedulerTickMillis,
            @Value("${npdev.auth.enabled:true}") boolean authEnabled,
            @Value("${npdev.api.max-body-bytes:262144}") int apiMaxBodyBytes,
            @Value("${npdev.api.max-json-depth:128}") int apiMaxJsonDepth,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.username:}") String datasourceUser,
            @Value("${spring.datasource.password:}") String datasourcePassword,
            @Value("${npdev.capability.circuit.open-after-failures:5}") int circuitOpenAfterFailures,
            @Value("${npdev.capability.circuit.open-seconds:30}") int circuitOpenSeconds,
            @Value("${npdev.capability.bulkhead.max-concurrent-default:8}") int bulkheadMaxConcurrentDefault,
            @Value("${npdev.capability.idempotency.max-bytes:16384}") int idempotencyMaxBytes,
            @Value("${npdev.capability.policy-overrides-json:}") String capabilityPolicyOverridesJson
    ) {
        return new RuntimeSettings(
                mode,
                schedulerEnabled,
                schedulerBatchLimit,
                schedulerTickMillis,
                authEnabled,
                apiMaxBodyBytes,
                apiMaxJsonDepth,
                datasourceUrl,
                datasourceUser,
                datasourcePassword,
                circuitOpenAfterFailures,
                circuitOpenSeconds,
                bulkheadMaxConcurrentDefault,
                idempotencyMaxBytes,
                capabilityPolicyOverridesJson
        );
    }

    @Bean
    public CapabilityPolicyOverrides capabilityPolicyOverrides(RuntimeSettings runtimeSettings, JsonCodec jsonCodec) {
        return CapabilityPolicyOverrides.fromJson(runtimeSettings.capabilityPolicyOverridesJson(), jsonCodec);
    }
}
