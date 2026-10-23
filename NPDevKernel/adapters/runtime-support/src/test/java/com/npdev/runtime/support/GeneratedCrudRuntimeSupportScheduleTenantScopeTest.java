package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import jakarta.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-300: the durable schedule table is shared by every tenant and its payloads carry business ids.
 * GET /api/runtime/schedules listed it unscoped, so another tenant's USER read every tenant's schedules
 * (found by behaviour probe schedules-of-another-tenant-are-not-listed), and a forced drain
 * (POST .../process-due forceDue=true) fired every tenant's not-yet-due events. Now a non-SUPERUSER is
 * limited to its own tenant for both; an unforced drain still publishes whatever is already due.
 */
class GeneratedCrudRuntimeSupportScheduleTenantScopeTest {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    private JdbcDataSource dataSource;
    private GeneratedCrudRuntimeSupport support;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:reg300_schedules_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        execute("""
                CREATE TABLE npdev_scheduled_event (
                  id VARCHAR(191) NOT NULL PRIMARY KEY,
                  schedule_key VARCHAR(191) NOT NULL,
                  orchestration_name TEXT NOT NULL,
                  action_index INTEGER NOT NULL,
                  source_event_name VARCHAR(191) NOT NULL,
                  source_event_id VARCHAR(191),
                  trigger_correlation_id TEXT,
                  tenant_id TEXT,
                  event_name TEXT NOT NULL,
                  due_at TIMESTAMP NOT NULL,
                  payload TEXT NOT NULL,
                  status VARCHAR(191) NOT NULL DEFAULT 'PENDING',
                  attempt_count INTEGER NOT NULL DEFAULT 0,
                  created_at TIMESTAMP NOT NULL,
                  updated_at TIMESTAMP NOT NULL,
                  processed_at TIMESTAMP
                )
                """);
        insertSchedule("11111111-1111-4111-8111-111111111111", "tenant-a");
        insertSchedule("22222222-2222-4222-8222-222222222222", "tenant-b");
        support = new GeneratedCrudRuntimeSupport(() -> new CompiledModel("reg300", "1.0.0", "v1", Map.of()),
                kernelRunner(), null, null, null, dataSource);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aTenantListsOnlyItsOwnSchedules() {
        callAs("tenant-a", List.of("USER"));
        assertEquals(Set.of("tenant-a"), tenantsListed());
    }

    @Test
    void aSuperUserListsEveryTenantsSchedules() {
        callAs("tenant-a", List.of("SUPERUSER"));
        assertEquals(Set.of("tenant-a", "tenant-b"), tenantsListed());
    }

    @Test
    void aForcedDrainFiresOnlyTheCallersTenant() throws SQLException {
        callAs("tenant-a", List.of("ADMIN"));
        support.processDueScheduledEvents(Boolean.TRUE, 100);
        assertEquals("PENDING", status("tenant-b"), "tenant-a must not fire tenant-b's not-yet-due event");
        // Claimed and attempted (whether this minimal kernel's publish then succeeds is not the point).
        org.junit.jupiter.api.Assertions.assertNotEquals("PENDING", status("tenant-a"));
    }

    private Set<String> tenantsListed() {
        return support.listScheduledEvents(100, 0).stream()
                .map(row -> String.valueOf(row.get("tenantId")))
                .collect(Collectors.toSet());
    }

    private void insertSchedule(String id, String tenant) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO npdev_scheduled_event (id, schedule_key, orchestration_name, action_index, "
                             + "source_event_name, tenant_id, event_name, due_at, payload, created_at, updated_at) "
                             + "VALUES (?, ?, 'FollowUp', 0, 'Src', ?, 'Due', DATEADD('HOUR', 1, CURRENT_TIMESTAMP), "
                             + "'{\"secret\":\"" + tenant + "\"}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            statement.setString(1, id);
            statement.setString(2, "key-" + id);
            statement.setString(3, tenant);
            statement.executeUpdate();
        }
    }

    private String status(String tenant) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM npdev_scheduled_event WHERE tenant_id = ?")) {
            statement.setString(1, tenant);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void callAs(String tenantId, List<String> roles) {
        Map<String, Object> claims = Map.of("tenant_id", tenantId, "actor_id", "probe-actor", "roles", roles);
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> CLAIMS_ATTRIBUTE.equals(args[0]) ? claims : null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static KernelRunner kernelRunner() {
        EventsInMemory events = new EventsInMemory();
        return new KernelRunner(events, (entityName, payload) -> List.of(), flowName -> java.util.Optional.empty(),
                (call, state) -> com.npdev.kernel.CapabilityResult.success(null), events);
    }

    /** KernelRunner takes the bus as the event store when it is one; a forced drain publishes through it. */
    private static final class EventsInMemory implements EventBus, com.npdev.kernel.ports.EventStore {
        private final List<com.npdev.kernel.events.EventEnvelope> appended = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(com.npdev.kernel.events.EventEnvelope event) {
        }

        @Override
        public void append(com.npdev.kernel.events.EventEnvelope event) {
            appended.add(event);
        }

        @Override
        public List<com.npdev.kernel.events.EventEnvelope> readByCorrelation(String correlationId) {
            return appended.stream().filter(e -> correlationId != null && correlationId.equals(e.correlationId())).toList();
        }

        @Override
        public List<com.npdev.kernel.events.EventEnvelope> readByEventName(String eventName) {
            return appended.stream().filter(e -> eventName != null && eventName.equals(e.eventName())).toList();
        }
    }
}
