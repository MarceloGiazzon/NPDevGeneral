package com.npdev.adapters.persistence.postgres;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEN-1: a flow write through the persistence capability must never overwrite a record that belongs
 * to another tenant. Ids leak (links, exports, logs), so "tenant B saves tenant A's id" has to be
 * refused rather than matching A's row by id alone and re-stamping it as B's.
 */
class PostgresPersistenceCapabilityAdapterTenantOverwriteTest {

    private SqlDialect previousDialect;
    private DataSource dataSource;
    private PostgresPersistenceCapabilityAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        previousDialect = SqlDialects.active();
        SqlDialects.setActive(H2Dialect.INSTANCE);
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:tenantoverwrite" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE notes (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64),"
                    + " name VARCHAR(200))");
            statement.execute("CREATE TABLE shared_items (id VARCHAR(64) PRIMARY KEY, name VARCHAR(200))");
        }
        adapter = new PostgresPersistenceCapabilityAdapter(dataSource, null, H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.setActive(previousDialect);
    }

    private static Map<String, Object> record(String id, String name, String tenant) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("name", name);
        if (tenant != null) {
            record.put("tenantId", tenant);
            record.put("tenant_id", tenant);
        }
        return record;
    }

    private String[] row(String table, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name, "
                     + (table.equals("notes") ? "tenant_id" : "NULL") + " FROM " + table + " WHERE id = '" + id + "'")) {
            assertTrue(rs.next(), "row " + id + " should exist");
            return new String[] {rs.getString(1), rs.getString(2)};
        }
    }

    @Test
    void tenantBCannotOverwriteTenantARecordThroughTheScopedSave() throws SQLException {
        adapter.save("Note", record("X", "A-original", "tenantA"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> adapter.save("Note", record("X", "B-overwrite", "tenantB")));

        assertTrue(refused.getMessage().contains("PERSISTENCE_RECORD_NOT_IN_TENANT"), refused.getMessage());
        assertFalse(refused.getMessage().contains("tenantA"), "must not reveal the owning tenant");
        assertEquals("A-original", row("notes", "X")[0]);
        assertEquals("tenantA", row("notes", "X")[1]);
    }

    @Test
    void scopedSaveStampsTheCallerTenantAndStillRefusesAnotherTenantsId() throws SQLException {
        adapter.save("Note", record("Y", "A-original", "tenantA"));

        Map<String, Object> forged = record("Y", "B-overwrite", null);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> adapter.save("Note", stamp(forged, "tenantB")));

        assertTrue(refused.getMessage().contains("PERSISTENCE_RECORD_NOT_IN_TENANT"), refused.getMessage());
        assertEquals("A-original", row("notes", "Y")[0]);
        assertEquals("tenantA", row("notes", "Y")[1]);
    }

    @Test
    void sameTenantStillUpdatesItsOwnRecord() throws SQLException {
        adapter.save("Note", record("Z", "first", "tenantA"));
        adapter.save("Note", record("Z", "second", "tenantA"));

        assertEquals("second", row("notes", "Z")[0]);
        assertEquals("tenantA", row("notes", "Z")[1]);
    }

    @Test
    void tablesWithoutATenantColumnStayUnscoped() throws SQLException {
        adapter.save("SharedItem", record("S", "one", null));
        adapter.save("SharedItem", record("S", "two", null));

        assertEquals("two", row("shared_items", "S")[0]);
    }

    private static Map<String, Object> stamp(Map<String, Object> source, String tenant) {
        Map<String, Object> stamped = new LinkedHashMap<>(source);
        stamped.put("tenantId", tenant);
        stamped.put("tenant_id", tenant);
        return stamped;
    }
}
