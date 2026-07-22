package com.npdev.adapters.circuit.postgres;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.capability.CircuitState;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresCircuitBreakerStateStoreTest {
    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE IF NOT EXISTS npdev_circuit_breaker (
                tenant_id TEXT NOT NULL,
                capability TEXT NOT NULL,
                operation TEXT NOT NULL,
                state TEXT NOT NULL,
                consecutive_failures INTEGER NOT NULL DEFAULT 0,
                opened_at_ms BIGINT NOT NULL DEFAULT 0,
                last_failure_at_ms BIGINT NOT NULL DEFAULT 0,
                half_open_allowed_at_ms BIGINT NOT NULL DEFAULT 0,
                half_open_trial_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (tenant_id, capability, operation)
            )
            """
    };

    private PostgresCircuitBreakerStateStore store;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(dataSource, SCHEMA_SQL);
        PostgresTestSupport.truncate(dataSource, "npdev_circuit_breaker");
        store = new PostgresCircuitBreakerStateStore(dataSource);
    }

    @Test
    void putGetAndResetWorkWithCompositeKey() {
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");
        assertEquals(CircuitState.CLOSED, store.get(key).state());

        CircuitBreakerState open = new CircuitBreakerState(CircuitState.OPEN, 5, 1000L, 1200L, 31000L, 0);
        store.put(key, open);

        CircuitBreakerState loaded = store.get(key);
        assertEquals(CircuitState.OPEN, loaded.state());
        assertEquals(5, loaded.consecutiveFailures());
        assertEquals(1200L, loaded.lastFailureAtMs());

        CircuitBreakerState halfOpen = new CircuitBreakerState(CircuitState.HALF_OPEN, 5, 1000L, 1200L, 31000L, 1);
        store.put(key, halfOpen);
        assertEquals(CircuitState.HALF_OPEN, store.get(key).state());

        List<CircuitBreakerStateSummary> summaries = store.listStates("tenant-a", "persistence", "save", 10, 0);
        assertEquals(1, summaries.size());
        assertEquals(CircuitState.HALF_OPEN, summaries.get(0).state());

        store.reset(key);
        assertEquals(CircuitState.CLOSED, store.get(key).state());
    }

}
