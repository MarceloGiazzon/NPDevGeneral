package com.npdev.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REG-304: a flow payload can carry a Jackson {@code JsonNode} for an object/file field (the
 * ConfirmWidgetOrder flow's file fields arrived as a {@code NullNode}). Bound raw, H2 received a
 * Java-serialized blob and refused it ("Data conversion error converting JAVA_OBJECT to JSON"). A
 * JSON-null node now binds as SQL NULL and any other node as its JSON text.
 */
class PostgresPersistenceCapabilityAdapterJsonNodeBindTest {

    private static final String MODEL = "{"
            + "\"dslVersion\":\"1.0.0\",\"namespace\":\"jsonnode.test\",\"version\":\"1.0\","
            + "\"concepts\":[{\"name\":\"Doc\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
            + "{\"name\":\"payload\",\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"string\"}}}]}]}";

    private SqlDialect previousDialect;
    private DataSource dataSource;
    private PostgresPersistenceCapabilityAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        previousDialect = SqlDialects.active();
        SqlDialects.setActive(H2Dialect.INSTANCE);
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:jsonnode" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource = h2;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE docs (id VARCHAR(64) PRIMARY KEY, payload JSON)");
        }
        CompiledModel model = new ModelCompiler().compile(
                new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        adapter = new PostgresPersistenceCapabilityAdapter(dataSource, model, H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.setActive(previousDialect);
    }

    @Test
    void aJsonNullNodeIsStoredAsSqlNull() throws SQLException {
        Map<String, Object> record = new HashMap<>();
        record.put("id", "d-null");
        record.put("payload", NullNode.getInstance());
        adapter.save("Doc", record);

        assertNull(payloadText("d-null"));
    }

    @Test
    void anObjectNodeIsStoredAsAJsonObject() throws Exception {
        Map<String, Object> record = new HashMap<>();
        record.put("id", "d-obj");
        record.put("payload", new ObjectMapper().readTree("{\"a\":1,\"b\":\"x\"}"));
        adapter.save("Doc", record);

        assertEquals("{\"a\":1,\"b\":\"x\"}", payloadText("d-obj"));
    }

    private String payloadText(String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement("SELECT CAST(payload AS VARCHAR) FROM docs WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }
}
