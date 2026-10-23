package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.runtime.support.crud.orchestration.EventCreateOrchestration;
import com.npdev.runtime.support.crud.orchestration.OrchestrationActionExecutionResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-295 residual: an event create-orchestration with {@code uniqueFields} skips the create when a row
 * with the same unique value already exists. That existence check had no tenant predicate, so a row of
 * ANOTHER tenant silently skipped this tenant's create ({@code unique_exists}) -- a lost write and a
 * small cross-tenant existence oracle. The created row is stamped with the acting tenant, so the check
 * must be scoped to it.
 */
class GeneratedCrudRuntimeSupportCreateOrchestrationTenantUniqueTest {

    private JdbcDataSource dataSource;
    private GeneratedCrudRuntimeSupport support;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:reg295_orchestration_unique_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        execute("CREATE TABLE sku_codes (id UUID PRIMARY KEY, code VARCHAR(64), tenant_id VARCHAR(64) NOT NULL, "
                + "UNIQUE (tenant_id, code))");
        execute("INSERT INTO sku_codes (id, code, tenant_id) VALUES (RANDOM_UUID(), 'X-1', 'tenant-b')");
        support = new GeneratedCrudRuntimeSupport(() -> new CompiledModel("demo", "1.0.0", "v1", Map.of()),
                kernelRunner(), jdbcEntityManager(dataSource), null, null, dataSource);
    }

    @Test
    void anotherTenantsRowWithTheSameUniqueValueDoesNotSkipThisTenantsCreate() throws Exception {
        OrchestrationActionExecutionResult result = runAsTenant("tenant-a");

        assertEquals("created", result.reason(),
                "tenant-b's X-1 must not make tenant-a's create look like a duplicate");
        assertEquals(1, count("tenant-a"));
        assertEquals(1, count("tenant-b"));
    }

    @Test
    void theSameTenantsRowStillSkipsTheCreate() throws Exception {
        OrchestrationActionExecutionResult result = runAsTenant("tenant-b");

        assertEquals("unique_exists", result.reason());
        assertEquals(1, count("tenant-b"));
    }

    private OrchestrationActionExecutionResult runAsTenant(String tenant) throws Exception {
        EventCreateOrchestration action = new EventCreateOrchestration(
                "SkuCode",
                "sku_codes",
                Map.of(
                        "id", new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        "code", new CompiledField("code", "string", "String", false, false, false)
                ),
                Map.of("code", "$event.code"),
                List.of("code")
        );
        EventEnvelope envelope = EventEnvelope.of("SkuRequested", Map.of("code", "X-1"), "corr-1", "cause-1",
                "external", 0, Map.of(), tenant, null);
        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(
                "executeCreateOrchestrationAction",
                EventCreateOrchestration.class, EventEnvelope.class, Map.class);
        method.setAccessible(true);
        return (OrchestrationActionExecutionResult) method.invoke(support, action, envelope, envelope.payload());
    }

    private int count(String tenant) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM sku_codes WHERE tenant_id = ? AND code = 'X-1'")) {
            statement.setString(1, tenant);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner(
                (EventBus) event -> {
                },
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
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
