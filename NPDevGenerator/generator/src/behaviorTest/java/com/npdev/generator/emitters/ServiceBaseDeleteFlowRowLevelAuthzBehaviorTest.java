package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-49 residual (`docs/archive/programme-history/REMAINDER_CLOSURE_PLAN.md` §3.3): an automated BEHAVIORAL test of the
 * delete-flow arm, closing the one gap REG-49's withdrawal left open. REG-49 itself was a false
 * positive (a stale generated-code pack), but its withdrawal's own manual trace of the real
 * generated artifact and the real kernel exception hierarchy stopped short of an automated runtime
 * assertion -- "a careful manual trace ... not a structural grep -- but it stops short of an
 * automated JUnit runtime assertion". {@link ServiceBaseFlowRowLevelAuthzTest} (the create-flow
 * sibling of this test) proves only the STRUCTURAL fix (the enforcement call is emitted, and
 * precedes the flow call, as source text) -- it never compiles or runs the generated code. This
 * test does: it generates a real {@code WidgetServiceBase.java} (+ entity/DTOs) for a concept
 * declaring BOTH {@code access.write} and a delete-mode Flow, compiles it for real, and constructs
 * it with a REAL {@code DefaultConceptGateway}/{@code ConfiguredConceptGatewaySemanticPolicy} (not
 * test doubles) plus a {@code KernelRunner} that THROWS if ever invoked -- so the assertion is
 * "the flow's own KernelRunner call never happened", not merely "an exception was thrown before it
 * would have" (which a mis-ordered but still-both-running implementation could accidentally satisfy).
 *
 * <p>Behaviour, not shape (lesson #2): this proves the denial PROPAGATES OUT OF {@code delete()}
 * before {@code enforceWithDeleteFlow} runs, by making the flow's own execution path detonate if it
 * is ever reached, rather than asserting on the emitted source text's call order.
 */
final class ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest {

    @TempDir
    Path tempDir;

    private static final String MODEL_JSON = """
            {
              "namespace": "reg49residual",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Widget",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "ownerId", "type": "string", "required": true },
                    { "name": "label", "type": "string", "required": true }
                  ],
                  "access": {
                    "write": "ownerId == $user.id"
                  }
                }
              ],
              "capabilities": [
                { "name": "persistence", "type": "PersistenceCapability", "operations": ["save", "unique", "findById"] }
              ],
              "bindings": [
                { "capability": "persistence", "adapter": "repository" },
                { "capability": "eventBus", "adapter": "inproc" }
              ],
              "flows": [
                {
                  "name": "RetireWidget",
                  "input": { "concept": "Widget", "mode": "delete" },
                  "steps": [
                    { "name": "return-ok", "type": "return", "value": "true" }
                  ]
                }
              ]
            }
            """;

    @Test
    void deniedRowScopeDeletePropagatesBeforeTheDeleteFlowEverRuns() throws Exception {
        Path modelPath = Files.createTempFile(tempDir, "npdev-reg49residual-model-", ".json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = tempDir.resolve("out");
        Path migrations = tempDir.resolve("mig");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()),
                new SettingResolver(SettingStore.builder().build()))
                .generate(compiled, out, migrations, modelPath);

        String generated = Files.readString(out.resolve("src/main/java/com/npdev/generated/services/WidgetServiceBase.java"));
        assertTrue(generated.contains("enforceDeleteWithConceptGateway(\"Widget\", id);"),
                "sanity check -- the row-level gateway delete check must still be emitted: " + generated);
        assertTrue(generated.contains("enforceWithDeleteFlow(crudCtx, id);"),
                "sanity check -- the delete flow must still be wired: " + generated);

        writeHarnessStubsAndClasses(out);

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        // Deliberately NOT Files.walk(...) over the whole generated tree: GeneratorFacade emits a
        // full app's worth of files (controllers, OpenAPI, Spring config) this test has no interest
        // in and would otherwise have to stub Spring MVC/Data/Context wholesale to compile. Only
        // WidgetServiceBase's own direct compile-time dependencies are needed to prove this arm.
        Path servicesDir = out.resolve("src/main/java/com/npdev/generated/services");
        Path entitiesDir = out.resolve("src/main/java/com/npdev/generated/entities");
        Path dtosDir = out.resolve("src/main/java/com/npdev/generated/dtos");
        List<Path> sources = new java.util.ArrayList<>(List.of(
                servicesDir.resolve("WidgetServiceBase.java"),
                entitiesDir.resolve("Widget.java"),
                dtosDir.resolve("WidgetCreateRequest.java"),
                dtosDir.resolve("WidgetUpdateRequest.java"),
                out.resolve("src/main/java/org/springframework/beans/factory/ObjectProvider.java"),
                out.resolve("src/main/java/org/springframework/transaction/annotation/Transactional.java"),
                out.resolve("src/main/java/validation/WidgetDeleteFlowHarness.java")
        ));
        for (Path source : sources) {
            assertTrue(Files.isRegularFile(source), "expected generated/stub file missing: " + source);
        }

        // This test runs in its OWN isolated Gradle source set / Test task (see build.gradle's
        // sourceSets.behaviorTest) specifically so a REAL spring-web + jakarta-servlet-api can sit on
        // `java.class.path` here without ever being visible to the main `test` task's classpath, where
        // sibling tests hand-write their own stub classes under these exact same package/class names.
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "this test requires a JDK compiler, not a JRE");
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new javax.tools.DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-d", classesDir.toString(), "-classpath", System.getProperty("java.class.path"));
            Boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null,
                    fileManager.getJavaFileObjectsFromPaths(sources)).call();
            if (!Boolean.TRUE.equals(ok)) {
                StringBuilder report = new StringBuilder("generated WidgetServiceBase + harness must compile:\n");
                diagnostics.getDiagnostics().forEach(d -> report.append(d).append('\n'));
                throw new AssertionError(report.toString());
            }
        }

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest.class.getClassLoader()
        )) {
            Class<?> harness = Class.forName("validation.WidgetDeleteFlowHarness", true, loader);
            harness.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
    }

    private static void writeHarnessStubsAndClasses(Path outputRoot) throws Exception {
        write(outputRoot, "src/main/java/org/springframework/beans/factory/ObjectProvider.java", """
                package org.springframework.beans.factory;
                public interface ObjectProvider<T> {
                    T getIfAvailable();
                }
                """);
        write(outputRoot, "src/main/java/org/springframework/transaction/annotation/Transactional.java", """
                package org.springframework.transaction.annotation;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Transactional {}
                """);
        write(outputRoot, "src/main/java/validation/WidgetDeleteFlowHarness.java", """
                package validation;

                import com.npdev.generated.services.WidgetServiceBase;
                import com.npdev.kernel.CapabilityErrorKind;
                import com.npdev.kernel.CapabilityResult;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.KernelRunner;
                import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
                import com.npdev.kernel.concepts.ConceptGatewayTraceSink;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy;
                import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.AccessRules;
                import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition;
                import com.npdev.kernel.concepts.DefaultConceptGateway;
                import com.npdev.kernel.inproc.InMemoryConceptStore;
                import com.npdev.kernel.ports.AuditLogStore;
                import com.npdev.kernel.ports.PermissionEvaluator;
                import com.npdev.kernel.ports.TenantIsolationPolicy;
                import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
                import org.springframework.web.context.request.RequestContextHolder;
                import org.springframework.web.context.request.ServletRequestAttributes;

                import java.lang.reflect.InvocationHandler;
                import java.lang.reflect.Method;
                import java.lang.reflect.Proxy;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.Set;
                import java.util.UUID;
                import java.util.concurrent.atomic.AtomicBoolean;

                public final class WidgetDeleteFlowHarness {

                    public static void main(String[] args) {
                        InMemoryConceptStore store = new InMemoryConceptStore();

                        ConfiguredConceptGatewaySemanticPolicy semanticPolicy = new ConfiguredConceptGatewaySemanticPolicy(List.of(
                                new ConceptDefinition("Widget", Map.of(), List.of(), null, Set.of(),
                                        new AccessRules(null, "ownerId == $user.id"))
                        ));

                        DefaultConceptGateway conceptGateway = new DefaultConceptGateway(
                                store,
                                PermissionEvaluator.allowAll(),
                                TenantIsolationPolicy.STRICT_EQUALS,
                                AuditLogStore.noop(),
                                semanticPolicy,
                                ConceptGatewayTraceSink.noop()
                        );

                        UUID widgetId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                        // Seeded directly through the store, bypassing the gateway (the REG-48 "Vault"
                        // pattern): owned by "owner-a", never by the caller this harness uses below.
                        store.save(new ConceptRecord("Widget", widgetId.toString(), "default",
                                Map.of("id", widgetId.toString(), "ownerId", "owner-a", "label", "Widget One")));

                        // KernelRunner is a real, final kernel class -- it cannot be mocked or subclassed, so
                        // this wires a REAL KernelRunner through its own designed extension points instead of
                        // faking the whole class. The flow-lookup callback is the actual behavioural probe:
                        // if enforceWithDeleteFlow ever calls kernelRunner.execute("RetireWidget", ...), THIS
                        // is the first thing that real execution path touches, proving the flow was reached
                        // (not just that some exception happened to be thrown before it "would have" run).
                        AtomicBoolean flowLookupAttempted = new AtomicBoolean(false);
                        KernelRunner kernelRunner = new KernelRunner(
                                event -> { },
                                (entityName, payload) -> List.of(),
                                flowName -> {
                                    flowLookupAttempted.set(true);
                                    return Optional.empty();
                                },
                                (call, state) -> CapabilityResult.failure(
                                        "CAPABILITY_DISPATCHER_NOT_CONFIGURED",
                                        "no capability dispatcher wired in this behavioural-test harness",
                                        CapabilityErrorKind.NOT_FOUND,
                                        Map.of()
                                )
                        );

                        GeneratedCrudRuntimeSupport runtimeSupport =
                                new GeneratedCrudRuntimeSupport(dummyCompiledModel(), kernelRunner)
                                        .withConceptGateway(conceptGateway);

                        WidgetServiceBase service = new WidgetServiceBase(
                                runtimeSupport,
                                conceptGateway,
                                store,
                                kernelRunner,
                                Optional.empty(),
                                () -> null,
                                () -> null
                        );

                        // "owner-b" is a caller with full coarse DELETE permission (PermissionEvaluator.allowAll())
                        // but OUTSIDE this row's access.write scope ("ownerId == $user.id", and the row's
                        // ownerId is "owner-a") -- exactly LNCH13-F1/REG-49's residual arm.
                        bindRequestClaims("default", "owner-b", List.of("USER"));
                        try {
                            service.delete(widgetId);
                            throw new IllegalStateException(
                                    "delete() must throw for a caller outside the row's access.write scope");
                        } catch (ConceptGatewayAccessDeniedException expected) {
                            require(expected.code() != null && expected.code().contains("SCOPE_DENIED"),
                                    "expected a *_SCOPE_DENIED code, got: " + expected.code());
                        } finally {
                            RequestContextHolder.resetRequestAttributes();
                        }
                        require(!flowLookupAttempted.get(),
                                "enforceWithDeleteFlow must never reach KernelRunner.execute(\\"RetireWidget\\", "
                                        + "...) -- the flow-definition lookup WAS attempted, proving the flow's "
                                        + "own execution path ran even though the row-scope check should have "
                                        + "denied the caller first");
                    }

                    private static void bindRequestClaims(String tenantId, String actorId, List<String> roles) {
                        Map<String, Object> claims = Map.of(
                                "tenant_id", tenantId,
                                "actor_id", actorId,
                                "roles", roles
                        );
                        jakarta.servlet.http.HttpServletRequest request =
                                (jakarta.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                                        WidgetDeleteFlowHarness.class.getClassLoader(),
                                        new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class},
                                        (InvocationHandler) (proxy, method, methodArgs) -> {
                                            if ("getAttribute".equals(method.getName())) {
                                                return "npdev.auth.claims".equals(methodArgs[0]) ? claims : null;
                                            }
                                            return defaultReturnValue(method);
                                        });
                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                    }

                    private static Object defaultReturnValue(Method method) {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        return null;
                    }

                    private static void require(boolean condition, String message) {
                        if (!condition) {
                            throw new IllegalStateException(message);
                        }
                    }

                    private static com.npdev.dsl.v1.compiled.CompiledModel dummyCompiledModel() {
                        return new com.npdev.dsl.v1.compiled.CompiledModel(
                                "reg49residual-harness", "1.0.0", "1.0.0",
                                Map.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
                        );
                    }
                }
                """);
    }

    private static void write(Path outputRoot, String relativePath, String source) throws Exception {
        Path target = outputRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
    }
}
