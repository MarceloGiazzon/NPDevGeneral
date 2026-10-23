package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-287: the generated-CRUD scope.exists and conflicts() lookups ran with no tenant_id predicate,
 * so another tenant's row satisfied this tenant's scope.exists (and confirmed it existed) and another
 * tenant's booking blocked this tenant's slot; and every failure -- unknown concept, dotted field,
 * missing table -- was answered "not found / no conflict". Both run here against real H2 tables
 * through a JDBC-backed EntityManager, with the caller in tenant-a.
 */
class CrudReferenceLookupSupportTenantScopeTest {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    private static final UUID ROOM = UUID.randomUUID();
    private static final String SLOT = "2026-10-23T10:00:00Z";

    private DataSource dataSource;
    private CrudReferenceLookupSupport lookups;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:reg287_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        execute("CREATE TABLE categories (id UUID PRIMARY KEY, code VARCHAR(64), tenant_id VARCHAR(64) NOT NULL)");
        execute("CREATE TABLE bookings (id UUID PRIMARY KEY, room_id UUID, starts_at TIMESTAMP WITH TIME ZONE, "
                + "duration_minutes INT, tenant_id VARCHAR(64) NOT NULL)");

        EntityManager entityManager = jdbcEntityManager(dataSource);
        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                CrudReferenceLookupSupportTenantScopeTest::model, kernelRunner(), entityManager);
        lookups = new CrudReferenceLookupSupport(support, entityManager);
        callAsTenant("tenant-a");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anotherTenantsRowDoesNotSatisfyScopeExists() throws SQLException {
        execute("INSERT INTO categories VALUES ('" + UUID.randomUUID() + "', 'C1', 'tenant-b')");
        assertFalse(lookups.scopeExists("Category", "code", "C1"));

        execute("INSERT INTO categories VALUES ('" + UUID.randomUUID() + "', 'C1', 'tenant-a')");
        assertTrue(lookups.scopeExists("Category", "code", "C1"));
    }

    @Test
    void anotherTenantsBookingDoesNotBlockTheSlot() throws SQLException {
        execute("INSERT INTO bookings VALUES ('" + UUID.randomUUID() + "', '" + ROOM + "', TIMESTAMP WITH TIME ZONE '"
                + "2026-10-23 10:15:00+00', 30, 'tenant-b')");
        assertFalse(conflicts());

        execute("INSERT INTO bookings VALUES ('" + UUID.randomUUID() + "', '" + ROOM + "', TIMESTAMP WITH TIME ZONE '"
                + "2026-10-23 10:15:00+00', 30, 'tenant-a')");
        assertTrue(conflicts());
    }

    @Test
    void anUnknownConceptOrFieldIsUncheckableNotNotFound() {
        assertUnavailable("scope", () -> lookups.scopeExists("NoSuchConcept", "code", "C1"));
        assertUnavailable("scope", () -> lookups.scopeExists("Category", "parent.code", "C1"));
        assertUnavailable("conflict", () -> lookups.resourceHasConflict(
                "Booking", "noSuchField", ROOM, "startsAt", SLOT, "durationMinutes", 60, null, Map.of()));
    }

    @Test
    void aFailingQueryIsUncheckableNotNotFound() throws SQLException {
        execute("DROP TABLE categories");
        execute("DROP TABLE bookings");
        assertUnavailable("scope", () -> lookups.scopeExists("Category", "code", "C1"));
        assertUnavailable("conflict", this::conflicts);
    }

    private boolean conflicts() {
        return lookups.resourceHasConflict(
                "Booking", "roomId", ROOM, "startsAt", SLOT, "durationMinutes", 60, null, Map.of());
    }

    private static void assertUnavailable(String lookup, Runnable call) {
        CelInvariantEngine.LookupUnavailableException unavailable =
                assertThrows(CelInvariantEngine.LookupUnavailableException.class, call::run);
        assertEquals(lookup, unavailable.lookup());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static CompiledModel model() {
        CompiledConcept category = new CompiledConcept("Category", "Category", "categories", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("code", "string", "String", false, false, false)));
        CompiledConcept booking = new CompiledConcept("Booking", "Booking", "bookings", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("roomId", "uuid", "java.util.UUID", false, false, false),
                new CompiledField("startsAt", "datetime", "java.time.OffsetDateTime", false, false, false),
                new CompiledField("durationMinutes", "integer", "Integer", false, false, false)));
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put(category.getName(), category);
        concepts.put(booking.getName(), booking);
        return new CompiledModel("reg287", "1.0.0", "v1", concepts);
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
                                int columns = resultSet.getMetaData().getColumnCount();
                                while (resultSet.next()) {
                                    Object[] row = new Object[columns];
                                    for (int c = 0; c < columns; c++) {
                                        row[c] = resultSet.getObject(c + 1);
                                    }
                                    rows.add(columns == 1 ? row[0] : row);
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
