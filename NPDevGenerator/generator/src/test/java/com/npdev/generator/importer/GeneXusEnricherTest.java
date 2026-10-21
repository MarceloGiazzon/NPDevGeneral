package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMP-2: what a GeneXus export adds to a database draft, on a fixture small enough to read. */
class GeneXusEnricherTest {

    private static final String PROP = "<Property><Name>%s</Name><Value>%s</Value></Property>";

    // Single quotes stand for XML attribute quotes and are swapped for double quotes before parsing, so the rule
    // source below uses them as GeneXus string delimiters too (the rule scanner accepts either).
    private static final String TABLES = "<ExportFile><Objects>"
            + "<Object type='00972a17-9975-449e-aab1-d26165d51393' name='DomSituacao'><Properties>"
            + String.format(PROP, "IDEnumDefinedValues", "1, Normal, Normal; 2, Cancelado, Cancelado por falta")
            + "</Properties></Object>"
            + "<Object type='00972a17-9975-449e-aab1-d26165d51393' name='Id'><Properties>"
            + String.format(PROP, "ATTCUSTOMTYPE", "bas:Numeric") + "</Properties></Object>"
            + "<Object type='857ca50e-7905-0000-0007-c5d9ff2975ec' name='Pedido'>"
            + "<Part type='00000000-0000-0000-0002-000000000004'><Key><Item>PedidoId</Item></Key></Part></Object>"
            + "<Object type='857ca50e-7905-0000-0007-c5d9ff2975ec' name='PedidoItem'>"
            + "<Part type='00000000-0000-0000-0002-000000000004'><Key><Item>PedidoId</Item><Item>ItemSeq</Item></Key></Part></Object>"
            + "</Objects><Attributes>"
            + "<Attribute name='PedidoId' description='Pedido'><Properties>" + String.format(PROP, "idBasedOn", "Domain:Id") + "</Properties></Attribute>"
            + "<Attribute name='PedidoSituacao' description='Sit.'><Properties>" + String.format(PROP, "idBasedOn", "Domain:DomSituacao")
            + String.format(PROP, "ContextualTitle", "Situacao do pedido") + "</Properties></Attribute>"
            + "<Attribute name='PedidoValor' description='Valor'><Properties/></Attribute>"
            + "<Attribute name='ItemSeq' description='Seq'><Properties/></Attribute>"
            + "</Attributes></ExportFile>";

    private static final String MODULE = "<ExportFile><Objects>"
            + "<Object type='1db606f2-af09-4cf9-a3b5-b481519d28f6' name='Pedido' description='Pedido de venda'>"
            + "<Part type='264be5fb-1b28-4b25-a598-6ca900dd059f'><Level Name='Pedido' Type='Pedido' Description='Pedido de venda'>"
            + "<Attribute key='True'>PedidoId</Attribute><Attribute key='False'>PedidoSituacao</Attribute>"
            + "<Attribute key='False'>PedidoValor</Attribute>"
            + "<Level Name='Itens' Type='Itens' Description='Itens'><Attribute key='True'>ItemSeq</Attribute></Level></Level></Part>"
            + "<Part type='9b0a32a3-de6d-4be1-a4dd-1b85d3741534'><Source>"
            + "Parm(in:&amp;Mode, in:&amp;PedidoId);\n"
            + "NoAccept(PedidoId) if not &amp;PedidoId.IsEmpty();\n"
            + "// a comment; with a semicolon\n"
            + "Error('Valor negativo; nao pode') if PedidoValor &lt; 0;\n"
            + "Error('Ja cancelado') if not PedidoSituacao = DomSituacao.Cancelado;\n"
            + "Error('Codigo') if PedidoSituacao = 2 and PedidoValor &gt; 5;\n"
            + "Error('Funcao') if PedidoValor.IsEmpty();\n"
            + "PedidoSituacao = DomSituacao.Normal if &amp;Mode = TrnMode.Insert;\n"
            + "Msg('aviso');\n"
            + "</Source></Part></Object>"
            + "<Object type='1db606f2-af09-4cf9-a3b5-b481519d28f6' name='Orfa' description='Sem tabela'>"
            + "<Part type='264be5fb-1b28-4b25-a598-6ca900dd059f'><Level Name='Orfa' Type='Orfa' Description='x'>"
            + "<Attribute key='True'>Nada</Attribute></Level></Part></Object>"
            + "<Object type='84a12160-f59b-4ad7-a683-ea4481ac23e9' name='ProcSoma' description='Soma'>"
            + "<Part type='528d1c06-a9c2-420d-bd35-21dca83f12ff'><Source>for each Pedido\n  &amp;t += PedidoValor\nendfor</Source></Part></Object>"
            + "<Object type='c9584656-94b6-4ccd-890f-332d11fc2c25' name='TelaPedidos' description='Tela'>"
            + "<Part type='c44bd5ff-f918-415b-98e6-aca44fed84fa'><Source>Event Start\nEndEvent</Source></Part></Object>"
            + "<Object type='926a06b9-3417-4ab4-9f8c-09c2f626bb1c' name='QueryX' description='q'/>"
            + "</Objects></ExportFile>";

    private static GeneXusEnricher.Result result;

    private static Document xml(String text) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(text.replace('\'', '"').getBytes(StandardCharsets.UTF_8)));
    }

    @BeforeAll
    static void enrichTheFixture() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:imp2fixture;DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"Pedido\" (\"PedidoId\" INT PRIMARY KEY, \"PedidoSituacao\" SMALLINT, \"PedidoValor\" DECIMAL(9,2))");
            statement.execute("CREATE TABLE \"PedidoItem\" (\"PedidoId\" INT NOT NULL, \"ItemSeq\" INT NOT NULL, PRIMARY KEY (\"PedidoId\", \"ItemSeq\"))");
            DbIntrospector.Result draft = DbIntrospector.introspect(connection, "PUBLIC", "demo.gx", "test");
            result = GeneXusEnricher.enrich(draft.model(), draft.report(), xml(TABLES), xml(MODULE));
        }
    }

    private static JsonNode concept(String name) {
        for (JsonNode c : result.model().path("concepts")) {
            if (name.equals(c.path("name").asText())) {
                return c;
            }
        }
        return null;
    }

    private static JsonNode field(String concept, String name) {
        for (JsonNode f : concept(concept).path("fields")) {
            if (name.equals(f.path("name").asText())) {
                return f;
            }
        }
        return null;
    }

    private static JsonNode candidate(String kind, String needle) {
        for (JsonNode c : result.report().path("genexus").path("ruleCandidates")) {
            if (kind.equals(c.path("kind").asText()) && c.path("sourceText").asText().contains(needle)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void captionsComeFromTheContextualTitleThenTheDescription() {
        assertEquals("Situacao do pedido", field("Pedido", "pedidoSituacao").path("ui").path("label").asText());
        assertEquals("Valor", field("Pedido", "pedidoValor").path("ui").path("label").asText());
        assertEquals("Pedido de venda", concept("Pedido").path("ui").path("label").asText());
    }

    @Test
    void anEnumeratedDomainBecomesAnEnumAndTheLegacyCodesAreKept() {
        JsonNode f = field("Pedido", "pedidoSituacao");
        assertEquals("enum", f.path("type").asText());
        assertEquals("Normal", f.path("values").get(0).asText());
        assertEquals("Cancelado", f.path("values").get(1).path("value").asText());
        assertEquals("Cancelado por falta", f.path("values").get(1).path("label").asText());
        assertTrue(f.path("description").asText().contains("1=Normal; 2=Cancelado"));
        assertFalse(f.has("maxLength"));
        JsonNode e = result.report().path("genexus").path("enums").get(0);
        assertEquals("Cancelado", e.path("legacyCodes").path("2").asText());
        assertEquals(1, result.report().path("genexus").path("counts").path("domains").path("enumAppliedToFields").asInt());
    }

    @Test
    void anEnumDomainOnAnIdentityIsLeftAloneAndListed() throws Exception {
        String tables = TABLES.replace("Domain:Id", "Domain:DomSituacao");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:imp2identity;DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"Pedido\" (\"PedidoId\" INT PRIMARY KEY)");
            DbIntrospector.Result draft = DbIntrospector.introspect(connection, "PUBLIC", "demo.gx", "test");
            GeneXusEnricher.Result r = GeneXusEnricher.enrich(draft.model(), draft.report(), xml(tables), null);
            assertEquals(1, r.report().path("genexus").path("counts").path("domains").path("enumSkipped").asInt());
            assertEquals("integer", r.model().path("concepts").get(0).path("fields").get(0).path("type").asText());
        }
    }

    @Test
    void transactionsMapToTablesByKeySetAndOrphansAreSaidSo() {
        var tx = result.report().path("genexus").path("counts").path("transactions");
        assertEquals(2, tx.path("total").asInt());
        assertEquals(1, tx.path("mapped").asInt());
        assertEquals(1, tx.path("unmapped").asInt());
        assertEquals(3, tx.path("levels").asInt());
        assertEquals(2, tx.path("levelsMapped").asInt());
        assertEquals("PedidoItem", result.report().path("genexus").path("transactions").get(0).path("levels").get(1).path("concept").asText());
    }

    @Test
    void anErrorRuleBecomesTheNegatedInvariantAndStaysInTheReport() {
        JsonNode plain = candidate("invariant", "Valor negativo");
        assertEquals("translated-unverified", plain.path("confidence").asText());
        assertEquals("!(pedidoValor < 0)", plain.path("expression").asText());
        assertEquals("Valor negativo; nao pode", plain.path("message").asText());
        assertNull(concept("Pedido").get("invariants"), "candidates are never applied to the model");
    }

    @Test
    void notBindsTheWholeComparisonAndDomainValuesBecomeNames() {
        assertEquals("!(!(pedidoSituacao == 'Cancelado'))", candidate("invariant", "Ja cancelado").path("expression").asText());
    }

    @Test
    void aLegacyCodeComparedWithAnEnumFieldBecomesTheName() {
        assertEquals("!(pedidoSituacao == 'Cancelado' && pedidoValor > 5)", candidate("invariant", "Codigo").path("expression").asText());
    }

    @Test
    void aFunctionCallIsKeptAsSourceOnly() {
        JsonNode c = candidate("invariant", "Funcao");
        assertEquals("manual", c.path("confidence").asText());
        assertTrue(c.path("why").asText().contains("function"));
        assertNull(c.get("expression"));
    }

    @Test
    void aDefaultOnInsertIsACandidateAndPlumbingIsDropped() {
        JsonNode d = candidate("default", "TrnMode.Insert");
        assertEquals("pedidoSituacao", d.path("field").asText());
        assertEquals("'Normal'", d.path("value").asText());
        var rules = result.report().path("genexus").path("counts").path("rules");
        assertEquals(8, rules.path("statements").asInt());
        assertEquals(2, rules.path("notBusinessRule").asInt());
        assertEquals(3, rules.path("invariantTranslated").asInt());
        assertEquals(1, rules.path("invariantSourceOnly").asInt());
        assertEquals(1, rules.path("defaultCandidates").asInt());
        assertEquals(1, rules.path("manual").asInt());
    }

    @Test
    void proceduresPanelsAndQueriesAreInventoriedWithSourceAttached() {
        var port = result.report().path("genexus").path("counts").path("manualPort");
        assertEquals(1, port.path("procedures").asInt());
        assertEquals(1, port.path("webPanels").asInt());
        assertEquals(1, port.path("queries").asInt());
        String source = result.files().get("manual-port/procedures/ProcSoma.gx.txt");
        assertNotNull(source);
        assertTrue(source.contains("for each Pedido"));
        assertTrue(result.files().containsKey("manual-port/webpanels/TelaPedidos.gx.txt"));
    }

    @Test
    void statementsDropCommentsTagsAndSplitOutsideQuotes() {
        assertEquals(List.of("a = 'x;y'", "b = 2"),
                GeneXusEnricher.statements("[web]\n{ a = 'x;y'; // gone; really\n /* also; gone */ b = 2; }"));
    }

    @Test
    void theCommandLineWritesTheEnrichedDraft(@TempDir Path dir) throws Exception {
        Path draft = Files.createDirectories(dir.resolve("draft"));
        Files.writeString(draft.resolve("model.json"), result.model().toString());
        Files.writeString(draft.resolve("import-report.json"), "{\"tables\":[],\"manualItems\":[],\"counts\":{}}");
        Files.writeString(dir.resolve("tables.xml"), TABLES.replace('\'', '"'));
        Files.writeString(dir.resolve("module.xml"), MODULE.replace('\'', '"'));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EnrichGeneXusMain.run(new String[] {"--draft", draft.toString(), "--tables-xml", dir.resolve("tables.xml").toString(),
                "--module-xml", dir.resolve("module.xml").toString(), "--out", dir.resolve("out").toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(dir.resolve("out/model.json")) && Files.exists(dir.resolve("out/import-report.json")));
        assertTrue(Files.exists(dir.resolve("out/manual-port/procedures/ProcSoma.gx.txt")));
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("npdev import genexus:"));
    }

    @Test
    void badArgumentsAndMissingFilesExitTwo(@TempDir Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(2, EnrichGeneXusMain.run(new String[] {"--bogus"}, o, e));
        assertEquals(2, EnrichGeneXusMain.run(new String[] {"--draft", dir.toString()}, o, e));
        assertEquals(2, EnrichGeneXusMain.run(new String[] {"--draft", dir.toString(), "--tables-xml", dir.resolve("x.xml").toString(),
                "--out", dir.resolve("o").toString()}, o, e));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("usage:"));
    }
}
