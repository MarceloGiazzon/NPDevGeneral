package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-294 (a): the lifecycle current-status read had no tenant_id predicate, and any failure was
 * answered null -- which validateLifecycleTransitions takes as "no previous status" and so skipped
 * the undeclared-transition, requiredPayload and guard checks (fail-open). Real H2 table through a
 * JDBC-backed EntityManager, caller in tenant-a.
 */
class CrudLifecycleSupportTenantScopeTest {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    private DataSource dataSource;
    private GeneratedCrudRuntimeSupport support;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:reg294_lifecycle_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        execute("CREATE TABLE appointments (id UUID PRIMARY KEY, status VARCHAR(64), check_in_time VARCHAR(64), "
                + "tenant_id VARCHAR(64) NOT NULL)");
        support = new GeneratedCrudRuntimeSupport(CrudLifecycleSupportTenantScopeTest::model, kernelRunner(),
                jdbcEntityManager(dataSource), null, null, null, new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                (uniqueValueLookup, conflictLookup) -> (entityName, payload) -> List.of());
        callAsTenant("tenant-a");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theStatusReadIsScopedToTheCallersTenant() throws SQLException {
        UUID own = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        execute("INSERT INTO appointments (id, status, tenant_id) VALUES ('" + own + "', 'Completed', 'tenant-a')");
        execute("INSERT INTO appointments (id, status, tenant_id) VALUES ('" + foreign + "', 'Completed', 'tenant-b')");

        // Control: tenant-a's own row is read, so Completed -> Scheduled is refused as undeclared.
        assertTrue(transitionViolations(own, "Scheduled").stream()
                .anyMatch(v -> "status_transition_not_allowed".equals(v.invariant())));
        // tenant-b's row is invisible to tenant-a's status read.
        assertTrue(transitionViolations(foreign, "Scheduled").isEmpty());
    }

    @Test
    void aFailedStatusReadRefusesInsteadOfSkippingTheTransitionCheck() throws SQLException {
        execute("DROP TABLE appointments");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transitionViolations(UUID.randomUUID(), "Scheduled"));
        assertTrue(failure.getMessage().contains("appointments"), failure.getMessage());
    }

    private List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> transitionViolations(UUID id, String nextStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("__id", id);
        payload.put("status", nextStatus);
        return support.validateEntityDetailed("Appointment", payload,
                        (entity, field, value, currentId, rawPayload) -> false)
                .stream()
                .filter(v -> v.invariant() != null && v.invariant().startsWith("status_transition"))
                .toList();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static CompiledModel model() {
        CompiledEntity appointment = new CompiledEntity(
                "Appointment", "Appointment", "appointments",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("status", "enum", "String", false, true, false,
                                List.of("Scheduled", "CheckedIn", "Completed"), null, null, null, null, List.of()),
                        new CompiledField("checkInTime", "string", "String", false, false, false)
                ),
                List.of(),
                List.of(),
                new CompiledLifecycle(
                        "status",
                        List.of(
                                new CompiledStateMachineState("Scheduled", "Scheduled", true, false, Map.of()),
                                new CompiledStateMachineState("CheckedIn", "Checked In", false, false, Map.of()),
                                new CompiledStateMachineState("Completed", "Completed", false, true, Map.of())
                        ),
                        List.of(
                                new CompiledStateTransition("Scheduled", "CheckedIn", List.of("checkInTime"),
                                        "AppointmentCheckedIn", null, "Check In", Map.of()),
                                new CompiledStateTransition("CheckedIn", "Completed", List.of(),
                                        "AppointmentCompleted", null, "Complete", Map.of())
                        )
                )
        );
        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(appointment.getName(), appointment);
        return new CompiledModel("reg294", "1.0.0", "v1", entities);
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner((EventBus) event -> {
        }, new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        });
    }

    private static void callAsTenant(String tenantId) {
        Map<String, Object> claims = Map.of("tenant_id", tenantId);
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

    /** Just enough EntityManager for native queries: ":name" parameters become JDBC "?" in order. */
    private static EntityManager jdbcEntityManager(DataSource dataSource) {
        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> "createNativeQuery".equals(method.getName())
                        ? jdbcQuery(dataSource, (String) args[0])
                        : null);
    }

    private static Query jdbcQuery(DataSource dataSource, String namedSql) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile(":(\\w+)").matcher(namedSql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        String sql = matcher.replaceAll("?");
        Map<String, Object> values = new HashMap<>();
        return (Query) Proxy.newProxyInstance(
                Query.class.getClassLoader(),
                new Class<?>[]{Query.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setParameter" -> {
                        values.put((String) args[0], args[1]);
                        yield proxy;
                    }
                    case "setMaxResults" -> proxy;
                    case "getResultList" -> {
                        try (Connection connection = dataSource.getConnection();
                             PreparedStatement statement = connection.prepareStatement(sql)) {
                            for (int i = 0; i < names.size(); i++) {
                                statement.setObject(i + 1, values.get(names.get(i)));
                            }
                            List<Object> rows = new ArrayList<>();
                            try (ResultSet resultSet = statement.executeQuery()) {
                                while (resultSet.next()) {
                                    rows.add(resultSet.getObject(1));
                                }
                            }
                            yield rows;
                        } catch (SQLException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                    default -> null;
                });
    }
}
