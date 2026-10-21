package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMP-1: introspection of a legacy-shaped H2 schema -> a draft model that validates, plus an honest report. */
class DbIntrospectorTest {

    private static final String[] DDL = {
            "CREATE TABLE CLIENTES (CLICOD NUMERIC(8,0) NOT NULL PRIMARY KEY, CLINOM VARCHAR(60) NOT NULL, "
                    + "CLIEMAIL VARCHAR(80), CLISALDO NUMERIC(12,2), CLIATIVO BOOLEAN DEFAULT TRUE, CLIFOTO BLOB)",
            "CREATE UNIQUE INDEX UQ_CLIEMAIL ON CLIENTES (CLIEMAIL)",
            "CREATE TABLE PEDIDO_ITEM (PEDCOD INTEGER NOT NULL, ITEMSEQ INTEGER NOT NULL, QTD NUMERIC(10,3), "
                    + "PRIMARY KEY (PEDCOD, ITEMSEQ))",
            // quoted: H2 upper-cases unquoted names, and GeneXus-style mixed case is the shape under test
            "CREATE TABLE \"PedidoCab\" (\"PedId\" BIGINT NOT NULL PRIMARY KEY, \"CliCod\" NUMERIC(8,0) NOT NULL, "
                    + "\"PedData\" DATE, \"PedHora\" TIME, \"PedTs\" TIMESTAMP, "
                    + "CONSTRAINT FK_PED_CLI FOREIGN KEY (\"CliCod\") REFERENCES CLIENTES (CLICOD))",
            "CREATE UNIQUE INDEX UQ_PED_DATA_CLI ON \"PedidoCab\" (\"PedData\", \"CliCod\")",
            "CREATE TABLE SEM_CHAVE (NOTA VARCHAR(20))",
            "CREATE VIEW V_CLIENTES AS SELECT CLICOD FROM CLIENTES"
    };

    private static DbIntrospector.Result drafted(String dbName) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : DDL) {
                    statement.execute(ddl);
                }
            }
            return DbIntrospector.introspect(connection, "PUBLIC", "legacy.shop", "jdbc:h2:mem:" + dbName);
        }
    }

    private static JsonNode concept(JsonNode model, String name) {
        return StreamSupport.stream(model.get("concepts").spliterator(), false)
                .filter(c -> name.equals(c.get("name").asText())).findFirst().orElseThrow();
    }

    private static JsonNode field(JsonNode concept, String name) {
        return StreamSupport.stream(concept.get("fields").spliterator(), false)
                .filter(f -> name.equals(f.get("name").asText())).findFirst().orElseThrow();
    }

    @Test
    void legacyNamesBecomeReadableAndTheOriginalStaysInTheDescriptionAndReport() throws Exception {
        JsonNode model = drafted("imp1a").model();
        JsonNode clientes = concept(model, "Clientes");
        JsonNode nome = field(clientes, "clinom");
        assertTrue(nome.get("description").asText().contains("CLIENTES.CLINOM"));
        assertEquals("string", nome.get("type").asText());
        assertEquals(60, nome.get("maxLength").asInt());
        assertTrue(nome.get("required").asBoolean());
        // mixed-case legacy names keep their word boundaries
        assertNotNull(concept(model, "PedidoCab"));
        assertEquals("pedId", field(concept(model, "PedidoCab"), "pedId").get("name").asText());
    }

    @Test
    void typesFollowTheDocumentedMappingAndUnmappedColumnsAreListedNotGuessed() throws Exception {
        DbIntrospector.Result result = drafted("imp1b");
        JsonNode clientes = concept(result.model(), "Clientes");
        assertEquals("integer", field(clientes, "clicod").get("type").asText(), "NUMERIC(8,0) -> integer");
        assertTrue(field(clientes, "clicod").get("id").asBoolean());
        JsonNode saldo = field(clientes, "clisaldo");
        assertEquals("decimal", saldo.get("type").asText());
        assertEquals(12, saldo.get("precision").asInt());
        assertEquals(2, saldo.get("scale").asInt());
        assertEquals("boolean", field(clientes, "cliativo").get("type").asText());
        assertEquals("long", field(concept(result.model(), "PedidoCab"), "pedId").get("type").asText());

        List<String> unmapped = StreamSupport.stream(result.report().get("unmappedColumns").spliterator(), false)
                .map(u -> u.get("table").asText() + "." + u.get("column").asText()).toList();
        assertTrue(unmapped.contains("CLIENTES.CLIFOTO"), unmapped.toString());
        assertTrue(unmapped.contains("PedidoCab.PedHora"), unmapped.toString());
        assertFalse(StreamSupport.stream(clientes.get("fields").spliterator(), false)
                .anyMatch(f -> f.get("name").asText().equals("clifoto")), "an unmapped column must not appear in the model");
    }

    @Test
    void singleColumnForeignKeysBecomeReferencesAndUniquesAreKept() throws Exception {
        JsonNode model = drafted("imp1c").model();
        JsonNode cliCod = field(concept(model, "PedidoCab"), "cliCod");
        assertEquals("reference", cliCod.get("type").asText());
        assertEquals("Clientes", cliCod.get("reference").get("target").asText());
        assertTrue(field(concept(model, "Clientes"), "cliemail").get("unique").asBoolean());
        JsonNode indexes = concept(model, "PedidoCab").get("indexes");
        assertNotNull(indexes);
        assertEquals(List.of("pedData", "cliCod"),
                StreamSupport.stream(indexes.get(0).get("fields").spliterator(), false).map(JsonNode::asText).toList());
    }

    @Test
    void compositeOrMissingPrimaryKeysGetASyntheticIdAndAWarning() throws Exception {
        DbIntrospector.Result result = drafted("imp1d");
        for (String name : List.of("PedidoItem", "SemChave")) {
            JsonNode id = field(concept(result.model(), name), "id");
            assertEquals("uuid", id.get("type").asText());
            assertTrue(id.get("description").asText().startsWith("Synthetic identity"));
        }
        String warnings = result.report().get("warnings").toString();
        assertTrue(warnings.contains("PEDIDO_ITEM") && warnings.contains("SEM_CHAVE"), warnings);
    }

    @Test
    void defaultsViewsAndTheTypeTableAreReportedNotImported() throws Exception {
        DbIntrospector.Result result = drafted("imp1e");
        assertTrue(result.report().get("defaultsNotImported").toString().contains("CLIATIVO"));
        assertTrue(result.report().get("manualItems").toString().contains("V_CLIENTES"));
        assertTrue(result.report().get("typeMapping").size() > 5);
        assertEquals(4, result.report().get("counts").get("tables").asInt());
        assertEquals(1, result.report().get("counts").get("viewsSkipped").asInt());
    }

    @Test
    void theDraftPassesTheDslSemanticValidator() throws Exception {
        DbIntrospector.Result result = drafted("imp1f");
        ModelAst ast = new JsonModelParser().parse(result.model());
        List<String> errors = new SemanticValidator().validate(ast);
        assertEquals(List.of(), errors);
    }

    @Test
    void aPasswordInTheUrlNeverReachesTheReport() {
        assertEquals("jdbc:sqlserver://h;user=u;password=***;db=x", IntrospectDbMain.redact("jdbc:sqlserver://h;user=u;password=s3cret;db=x"));
    }

    @Test
    void namesThePlatformWouldRejectAreSplitSoTheDraftValidates() {
        assertEquals("ruaRecRe_stricao", DbIntrospector.neutral("ruaRecRestricao"));
        assertEquals("clienteNome", DbIntrospector.neutral("clienteNome"));
        assertFalse(DbIntrospector.neutral("interestRate").toLowerCase().contains("rest"));
    }
}
