package com.finalexec.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generated.controllers.GeneratedConceptCrudController;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REG-244 Phase 4C: {@code GeneratedConceptCrudController}'s live-concept fallback
 * ({@code resolveBinding} / {@code toConceptBinding}), exercised directly against the REAL generated
 * class for whichever sample app this test gets assembled into -- standalone MockMvc (no Spring Boot
 * context, no auth wiring needed), same shape as {@code MetadataHotSwapControllerStandaloneTest}. The
 * {@link ApplicationContext} mock answers ANY {@code getBean(Class)} call with a mock of that class,
 * so this test needs no knowledge of the assembled app's own baked concepts/service classes -- it
 * only exercises the fallback path for a concept the generator never baked in.
 */
class GeneratedConceptCrudControllerLiveConceptTest {

    private String url;
    private DataSource dataSource;

    private static final String EMPTY_MODEL = """
            { "namespace": "reg244.phase4c.controller", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
              { "name": "Placeholder", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true }
              ] }
            ] }
            """;

    private static final String MODEL_WITH_WIDGET = """
            { "namespace": "reg244.phase4c.controller", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
              { "name": "Placeholder", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true }
              ] },
              { "name": "Widget", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "label", "type": "string", "required": true },
                { "name": "quantity", "type": "int" }
              ] }
            ] }
            """;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        SqlDialects.setActive(H2Dialect.INSTANCE);
        dataSource = new DataSource() {
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

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.resetActiveForTesting();
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-live-concept-controller-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    @SuppressWarnings("unchecked")
    private static ApplicationContext anyBeanMockingContext() {
        ApplicationContext context = Mockito.mock(ApplicationContext.class);
        when(context.getBean(any(Class.class))).thenAnswer(invocation -> Mockito.mock(invocation.getArgument(0, Class.class)));
        return context;
    }

    @Test
    void newlyProvisionedConceptIsCrudReachableThroughTheRealGeneratedController() throws Exception {
        CompiledModel oldModel = compile(EMPTY_MODEL);
        CompiledModel newModel = compile(MODEL_WITH_WIDGET);
        com.finalexec.db.NewConceptSchemaProvisioner.Result provisioned =
                com.finalexec.db.NewConceptSchemaProvisioner.provision(dataSource, oldModel, newModel);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("Widget"), provisioned.provisioned());

        ModelHolder modelHolder = new ModelHolder(newModel);
        ConceptGateway conceptGateway = new DefaultConceptGateway(new com.finalexec.db.JdbcBusinessConceptStore(dataSource, newModel));
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any())).thenReturn(ExecutionContext.of("tenant-a", "test-actor"));

        GeneratedConceptCrudController controller = new GeneratedConceptCrudController(
                anyBeanMockingContext(), modelHolder, conceptGateway, runtimeContextService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        ObjectMapper objectMapper = new ObjectMapper();

        String createBody = objectMapper.writeValueAsString(java.util.Map.of("label", "First Widget", "quantity", 3));
        String createResponse = mockMvc.perform(post("/api/concepts/widgets")
                        .contentType("application/json").content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("First Widget"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/concepts/widgets/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3));

        // Partial update: only "quantity" supplied -- "label" must survive (the same contract the
        // batch setField endpoint depends on).
        String updateBody = objectMapper.writeValueAsString(java.util.Map.of("quantity", 9));
        mockMvc.perform(put("/api/concepts/widgets/" + id)
                        .contentType("application/json").content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("First Widget"))
                .andExpect(jsonPath("$.quantity").value(9));

        mockMvc.perform(delete("/api/concepts/widgets/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/concepts/widgets/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void aConceptNameMatchingNeitherTheBakedMapNorTheLiveModelIsStillUnknown() throws Exception {
        CompiledModel model = compile(EMPTY_MODEL);
        ModelHolder modelHolder = new ModelHolder(model);
        ConceptGateway conceptGateway = new DefaultConceptGateway(new com.finalexec.db.JdbcBusinessConceptStore(dataSource, model));
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any())).thenReturn(ExecutionContext.of("tenant-a", "test-actor"));

        GeneratedConceptCrudController controller = new GeneratedConceptCrudController(
                anyBeanMockingContext(), modelHolder, conceptGateway, runtimeContextService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/concepts/definitely-not-a-real-concept"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_concept"));
    }
}
