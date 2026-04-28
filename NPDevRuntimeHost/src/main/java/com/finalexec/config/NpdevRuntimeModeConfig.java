package com.finalexec.config;

import com.npdev.adapters.audit.inproc.InProcAuditLogStore;
import com.npdev.adapters.audit.postgres.PostgresAuditLogStore;
import com.npdev.adapters.bulkhead.inproc.InProcBulkheadStore;
import com.npdev.adapters.bulkhead.postgres.PostgresAdvisoryBulkheadStore;
import com.npdev.adapters.circuit.inproc.InProcCircuitBreakerStateStore;
import com.npdev.adapters.circuit.postgres.PostgresCircuitBreakerStateStore;
import com.npdev.adapters.eventstore.postgres.PostgresEventStore;
import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.adapters.flowinstance.inproc.InProcCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.adapters.flowinstance.postgres.PostgresCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.postgres.PostgresFlowInstanceStore;
import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.adapters.idempotency.postgres.PostgresIdempotencyStore;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.adapters.tracestore.PersistentExecutionTracer;
import com.npdev.adapters.tracestore.postgres.PostgresTraceStore;
import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration
public class NpdevRuntimeModeConfig {

    @Bean
    @Profile("!postgres")
    public EventStore inProcEventStore() {
        return new InProcEventStore();
    }

    @Bean
    @Profile("postgres")
    public EventStore postgresEventStore(DataSource dataSource) {
        return new PostgresEventStore(dataSource);
    }

    @Bean
    @Profile("!postgres")
    public InProcExecutionTracer inProcExecutionTracer() {
        return new InProcExecutionTracer();
    }

    @Bean
    @Profile("!postgres")
    public FlowInstanceStore inProcFlowInstanceStore() {
        return new InProcFlowInstanceStore();
    }

    @Bean
    @Profile("!postgres")
    public CorrelationOwnershipStore inProcCorrelationOwnershipStore() {
        return new InProcCorrelationOwnershipStore();
    }

    @Bean
    @Profile("postgres")
    public FlowInstanceStore postgresFlowInstanceStore(DataSource dataSource) {
        return new PostgresFlowInstanceStore(dataSource);
    }

    @Bean
    @Profile("postgres")
    public CorrelationOwnershipStore postgresCorrelationOwnershipStore(DataSource dataSource) {
        return new PostgresCorrelationOwnershipStore(dataSource);
    }

    @Bean
    @Profile("postgres")
    public TraceStore postgresTraceStore(DataSource dataSource) {
        return new PostgresTraceStore(dataSource);
    }

    @Bean
    @Profile("!postgres")
    public AuditLogStore inProcAuditLogStore() {
        return new InProcAuditLogStore();
    }

    @Bean
    @Profile("postgres")
    public AuditLogStore postgresAuditLogStore(DataSource dataSource) {
        return new PostgresAuditLogStore(dataSource);
    }

    @Bean
    @Profile("!postgres")
    public CircuitBreakerStateStore inProcCircuitBreakerStateStore() {
        return new InProcCircuitBreakerStateStore();
    }

    @Bean
    @Profile("postgres")
    public CircuitBreakerStateStore postgresCircuitBreakerStateStore(DataSource dataSource) {
        return new PostgresCircuitBreakerStateStore(dataSource);
    }

    @Bean
    @Profile("!postgres")
    public BulkheadStore inProcBulkheadStore() {
        return new InProcBulkheadStore();
    }

    @Bean
    @Profile("postgres")
    public BulkheadStore postgresBulkheadStore(DataSource dataSource) {
        return new PostgresAdvisoryBulkheadStore(dataSource);
    }

    @Bean
    @Profile("!postgres")
    public IdempotencyStore inProcIdempotencyStore() {
        return new InProcIdempotencyStore();
    }

    @Bean
    @Profile("postgres")
    public IdempotencyStore postgresIdempotencyStore(DataSource dataSource) {
        return new PostgresIdempotencyStore(dataSource);
    }

    @Bean
    @Profile("postgres")
    public ExecutionTracer postgresExecutionTracer(TraceStore traceStore) {
        return new PersistentExecutionTracer(traceStore);
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
