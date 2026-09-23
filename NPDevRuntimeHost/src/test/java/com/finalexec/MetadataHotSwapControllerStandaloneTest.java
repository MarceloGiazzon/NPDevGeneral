package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.MetadataHotSwapController;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1.7 (roadmap Wave 1, "hot metadata swap"): the authenticated HTTP surface over
 * {@link RuntimeMetadataService#applyMetadataOnlyReload}. Mirrors
 * {@code RuntimeMetadataControllerStandaloneTest}'s standalone-MockMvc shape.
 */
class MetadataHotSwapControllerStandaloneTest {

    @TempDir
    Path appExternalRoot;

    private MockMvc mockMvc;
    private RuntimeMetadataService runtimeMetadataService;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final ExecutionContext executionContext = Mockito.mock(ExecutionContext.class);

    @BeforeEach
    void setUp() throws IOException {
        // REG-244 Phase 4B: SqlDialects.active() defaults to Postgres and memoizes the first
        // resolution for the whole JVM -- modelReloadProvisionsTableForNewBondFreeConceptWhenEnabled
        // below runs real DDL against H2 and needs the dialect pinned accordingly.
        SqlDialects.setActive(H2Dialect.INSTANCE);
        Path generatedResourcesRoot = appExternalRoot.resolve("npdev-generated/src/main/resources");
        writeFixture(generatedResourcesRoot.resolve("npdev/compiled-metadata.json"), compiledMetadataJson("old-namespace"));
        writeFixture(generatedResourcesRoot.resolve("npdev/metadata/index.json"), indexJson());
        writeFixture(generatedResourcesRoot.resolve("npdev/metadata/concepts.manifest.json"), conceptsManifestJson("Old Label"));

        runtimeMetadataService = new RuntimeMetadataService(
                new ObjectMapper(),
                generatedResourcesRoot.resolve("npdev/compiled-metadata.json").toString(),
                generatedResourcesRoot.resolve("npdev/metadata/index.json").toString(),
                generatedResourcesRoot.toString());

        when(runtimeContextService.currentContext(any())).thenReturn(executionContext);

        MetadataHotSwapController controller = new MetadataHotSwapController(
                runtimeMetadataService, runtimeContextService, new com.finalexec.config.ModelHolder(), false,
                dataSourceProvider(null), false);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SqlDialects.resetActiveForTesting();
    }

    @Test
    void applyRequiresSuperUserNotJustAdmin() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(false);
        when(executionContext.hasRole("ADMIN")).thenReturn(true); // ADMIN alone must NOT be enough

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/apply")
                        .contentType("application/json")
                        .content("{\"classification\":\"METADATA_ONLY\",\"classificationReasons\":[],\"metadataSourceRoot\":\""
                                + escapeJson(appExternalRoot.toString()) + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyRefusesWhenClassificationIsNotMetadataOnly() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);
        Path sourceRoot = stageNewMetadata("attempted", "Attempted Label");

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/apply")
                        .contentType("application/json")
                        .content("{\"classification\":\"MANUAL_REVIEW\",\"classificationReasons\":[\"dropped a column\"],\"metadataSourceRoot\":\""
                                + escapeJson(sourceRoot.toString()) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("NOT_METADATA_ONLY"));

        // Nothing touched -- the pre-existing catalog is still visible unchanged.
        assertOverviewNamespace("old-namespace");
    }

    @Test
    void applySwapsMetadataLiveAndStatusReflectsIt() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);
        when(executionContext.hasRole("ADMIN")).thenReturn(true);
        Path sourceRoot = stageNewMetadata("swapped", "Swapped Label");

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/apply")
                        .contentType("application/json")
                        .content("{\"classification\":\"METADATA_ONLY\",\"classificationReasons\":[\"no schema-shaped change\"],\"metadataSourceRoot\":\""
                                + escapeJson(sourceRoot.toString()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.metadataGeneration").value(1))
                .andExpect(jsonPath("$.catalogsUpdated.length()").value(3));

        assertOverviewNamespace("swapped");

        mockMvc.perform(get("/api/admin/runtime/metadata-hotswap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataGeneration").value(1));
    }

    @Test
    void statusRequiresAdminContext() throws Exception {
        when(executionContext.hasRole("ADMIN")).thenReturn(false);
        mockMvc.perform(get("/api/admin/runtime/metadata-hotswap/status"))
                .andExpect(status().isForbidden());
    }

    // REG-208 (B28 lift): the opt-OUT flag stays real even though the Spring property default
    // flipped to true (@Value("...:true")) -- these three tests construct the controller directly
    // with an explicit boolean, so they exercise the flag's own gating logic regardless of what the
    // property resolves to, proving: the flag genuinely disables the endpoint when false, auth is
    // still checked ahead of the flag, and the explicit opt-in genuinely works.

    @Test
    void modelReloadReturnsNotFoundWhenFlagIsDisabledEvenForSuperUser() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);

        mockMvc.perform(post("/api/admin/runtime/metadata-hotswap/model-reload")
                        .contentType("application/json")
                        .content("{\"modelPath\":\"" + escapeJson(appExternalRoot.resolve("model.json").toString()) + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("FULL_MODEL_RELOAD_DISABLED"));
    }

    @Test
    void modelReloadStillRequiresSuperUserEvenWhenFlagIsEnabled() throws Exception {
        MetadataHotSwapController enabledController = new MetadataHotSwapController(
                runtimeMetadataService, runtimeContextService, new com.finalexec.config.ModelHolder(), true,
                dataSourceProvider(null), false);
        MockMvc enabledMockMvc = MockMvcBuilders.standaloneSetup(enabledController).build();
        when(executionContext.hasRole("SUPERUSER")).thenReturn(false);

        enabledMockMvc.perform(post("/api/admin/runtime/metadata-hotswap/model-reload")
                        .contentType("application/json")
                        .content("{\"modelPath\":\"" + escapeJson(appExternalRoot.resolve("model.json").toString()) + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void modelReloadSwapsTheModelWhenExplicitlyEnabled() throws Exception {
        Path modelPath = appExternalRoot.resolve("model.json");
        writeFixture(modelPath, minimalModelJson());
        com.finalexec.config.ModelHolder modelHolder =
                new com.finalexec.config.ModelHolder(compiledModel(minimalModelJson()));
        MetadataHotSwapController enabledController = new MetadataHotSwapController(
                runtimeMetadataService, runtimeContextService, modelHolder, true,
                dataSourceProvider(null), false);
        MockMvc enabledMockMvc = MockMvcBuilders.standaloneSetup(enabledController).build();
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);

        enabledMockMvc.perform(post("/api/admin/runtime/metadata-hotswap/model-reload")
                        .contentType("application/json")
                        .content("{\"modelPath\":\"" + escapeJson(modelPath.toString()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.concepts").value(1))
                // REG-243: /model-reload swaps the live CompiledModel but never touches
                // RuntimeMetadataService's UI-facing catalogs (a build-time-only classification this
                // runtime module cannot perform) -- the response must say so plainly rather than let
                // "ok: true" be read as "fully applied".
                .andExpect(jsonPath("$.uiMetadataCatalogsRefreshed").value(false))
                // REG-244 Phase 4B: new-concept provisioning is off by default here (last constructor
                // arg false) -- both fields must still be NAMED, just empty, never omitted.
                .andExpect(jsonPath("$.conceptsProvisioned.length()").value(0))
                .andExpect(jsonPath("$.conceptsProvisioningFailed.length()").value(0));

        org.junit.jupiter.api.Assertions.assertEquals(1, modelHolder.get().getConcepts().size());
    }

    /** REG-244 Phase 4B: enabling the property with a real (H2) DataSource available makes a
     *  reload that adds a brand-new, bond-free concept also create its table, named in the response. */
    @Test
    void modelReloadProvisionsTableForNewBondFreeConceptWhenEnabled() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        Path modelPath = appExternalRoot.resolve("model-with-new-concept.json");
        String modelWithNewConcept = "{"
                + "\"dslVersion\":\"1.0.0\",\"namespace\":\"hotswap.test\",\"version\":\"1.0\","
                + "\"concepts\":[{\"name\":\"Thing\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true}]},"
                + "{\"name\":\"Widget\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true},"
                + "{\"name\":\"label\",\"type\":\"string\"}]}]"
                + "}";
        writeFixture(modelPath, modelWithNewConcept);
        com.finalexec.config.ModelHolder modelHolder =
                new com.finalexec.config.ModelHolder(compiledModel(minimalModelJson()));
        MetadataHotSwapController enabledController = new MetadataHotSwapController(
                runtimeMetadataService, runtimeContextService, modelHolder, true,
                dataSourceProvider(h2DataSource(url)), true);
        MockMvc enabledMockMvc = MockMvcBuilders.standaloneSetup(enabledController).build();
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);

        enabledMockMvc.perform(post("/api/admin/runtime/metadata-hotswap/model-reload")
                        .contentType("application/json")
                        .content("{\"modelPath\":\"" + escapeJson(modelPath.toString()) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.conceptsProvisioned[0]").value("Widget"))
                .andExpect(jsonPath("$.conceptsProvisioningFailed.length()").value(0));

        // SqlIdentifierSupport.tableName() pluralizes ("Widget" -> "widgets"); INFORMATION_SCHEMA is
        // H2's own fixed-case system catalog, unaffected by this connection's DATABASE_TO_UPPER=false.
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'widgets'")) {
            java.util.Set<String> columns = new java.util.HashSet<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            org.junit.jupiter.api.Assertions.assertTrue(columns.contains("label"), "columns were " + columns);
        }
    }

    private static ObjectProvider<DataSource> dataSourceProvider(DataSource dataSource) {
        return new ObjectProvider<>() {
            @Override
            public DataSource getObject() {
                return dataSource;
            }

            @Override
            public DataSource getObject(Object... args) {
                return dataSource;
            }

            @Override
            public DataSource getIfAvailable() {
                return dataSource;
            }
        };
    }

    private static DataSource h2DataSource(String url) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url);
            }

            @Override
            public Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException();
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
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static com.npdev.dsl.v1.compiled.CompiledModel compiledModel(String modelJson) throws Exception {
        com.npdev.dsl.v1.ast.ModelAst ast = new com.npdev.dsl.v1.parser.JsonModelParser()
                .parse(new ObjectMapper().readTree(modelJson));
        return new com.npdev.dsl.v1.compiler.ModelCompiler().compile(ast);
    }

    private static String minimalModelJson() {
        return "{"
                + "\"dslVersion\":\"1.0.0\",\"namespace\":\"hotswap.test\",\"version\":\"1.0\","
                + "\"concepts\":[{\"name\":\"Thing\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true}]}]"
                + "}";
    }

    private void assertOverviewNamespace(String expected) {
        String namespace = String.valueOf(runtimeMetadataService.overview().get("namespace"));
        org.junit.jupiter.api.Assertions.assertEquals(expected, namespace);
    }

    private Path stageNewMetadata(String namespace, String label) throws IOException {
        Path sourceRoot = appExternalRoot.resolve("reload-source-" + namespace);
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/compiled-metadata.json"), compiledMetadataJson(namespace));
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/metadata/index.json"), indexJson());
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/metadata/concepts.manifest.json"), conceptsManifestJson(label));
        return sourceRoot;
    }

    private static void writeFixture(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String compiledMetadataJson(String namespace) {
        return "{"
                + "\"namespace\":\"" + namespace + "\","
                + "\"dslVersion\":\"2.0\","
                + "\"version\":\"1\","
                + "\"catalogs\":{\"concepts\":{}}"
                + "}";
    }

    private static String indexJson() {
        return "{"
                + "\"metadataManifestVersion\":\"1.0.0\","
                + "\"metadataVersion\":\"1.0.0\","
                + "\"catalogs\":[{\"name\":\"concepts\",\"path\":\"npdev/metadata/concepts.manifest.json\",\"count\":1}]"
                + "}";
    }

    private static String conceptsManifestJson(String label) {
        return "{"
                + "\"metadataManifestVersion\":\"1.0.0\","
                + "\"metadataVersion\":\"1.0.0\","
                + "\"catalog\":\"concepts\","
                + "\"items\":[{\"name\":\"Thing\",\"label\":\"" + label + "\"}]"
                + "}";
    }
}
