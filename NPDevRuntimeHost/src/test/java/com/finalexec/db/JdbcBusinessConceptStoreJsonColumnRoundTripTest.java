package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-GC: pins a real bug found live while wiring the file-field delete/replace cascade -- a
 * JSON-column value (object/array/file DSL types) round-tripped through {@link
 * JdbcBusinessConceptStore} against H2 came back as a JSON-encoded STRING instead of the nested
 * Map/List it was saved as. Root cause was two-fold: (1) {@code isJsonColumnType} trusted the JDBC
 * driver's reported column type name, which H2 doesn't reliably report as "JSON" for a JSON
 * column; (2) even when detected, binding the JSON text as a {@code java.lang.String} makes H2's
 * JSON column treat it as a JSON *string value* (quoting/escaping it) rather than the object it
 * represents -- binding raw JSON bytes instead fixes both engines. A file field's handle silently
 * round-tripping as a string defeated {@code GeneratedCrudRuntimeSupport}'s delete/replace-cascade
 * extraction with no error at all, which is what surfaced this.
 */
class JdbcBusinessConceptStoreJsonColumnRoundTripTest {

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE docs (id UUID NOT NULL, attachment JSON, tags JSON, tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, true);
        CompiledFileMetadata fileMeta = new CompiledFileMetadata(List.of("text/plain"), null, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.fasterxml.jackson.databind.JsonNode",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                fileMeta
        );
        CompiledField tagsField = new CompiledField(
                "tags", "array", "com.fasterxml.jackson.databind.JsonNode",
                false, false, false
        );
        CompiledConcept doc = new CompiledConcept("Doc", "Doc", "docs", List.of(idField, attachmentField, tagsField));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(doc.getName(), doc));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    @Test
    void fileFieldHandleRoundTripsAsANestedMapNotAJsonString() {
        UUID id = UUID.randomUUID();
        Map<String, Object> handle = Map.of(
                "storeId", "file-store-inproc",
                "key", "dev/some-uuid",
                "contentType", "text/plain",
                "sizeBytes", 12,
                "originalName", "x.txt"
        );
        store.save(new ConceptRecord("Doc", id.toString(), "tenant-a", Map.of("attachment", handle, "tags", List.of())));

        Optional<ConceptRecord> found = store.findById("tenant-a", "Doc", id.toString());
        assertTrue(found.isPresent());
        Object roundTripped = found.get().data().get("attachment");

        assertInstanceOf(Map.class, roundTripped,
                "a file handle must round-trip as a nested Map, not a JSON-encoded String");
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTrippedMap = (Map<String, Object>) roundTripped;
        assertEquals("dev/some-uuid", roundTrippedMap.get("key"));
        assertEquals("file-store-inproc", roundTrippedMap.get("storeId"));
    }

    @Test
    void arrayFieldRoundTripsAsAListNotAJsonString() {
        UUID id = UUID.randomUUID();
        store.save(new ConceptRecord("Doc", id.toString(), "tenant-a",
                Map.of("tags", List.of("alpha", "beta"), "attachment", Map.of())));

        Optional<ConceptRecord> found = store.findById("tenant-a", "Doc", id.toString());
        assertTrue(found.isPresent());
        Object roundTripped = found.get().data().get("tags");

        assertInstanceOf(List.class, roundTripped, "an array field must round-trip as a List, not a JSON-encoded String");
        assertEquals(List.of("alpha", "beta"), roundTripped);
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
