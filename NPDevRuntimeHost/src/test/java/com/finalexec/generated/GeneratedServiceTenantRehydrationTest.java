package com.finalexec.generated;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.entities.ContactMessage;
import com.npdev.generated.services.ContactMessageServiceBase;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.KernelRunner;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QUAL-45 regression: {@code entityFromRecord} (service-base.mustache) must rehydrate tenantId from
 * the {@link ConceptRecord} itself, since tenantId is never written into record.data() at the write
 * side (see {@code JdbcBusinessConceptStoreTenantIsolationTest}'s own javadoc). Runs the real
 * generated {@code ContactMessageServiceBase} -- generated for this RuntimeHost gate's own default
 * verification sample (simple-contact-intake) -- against a real DefaultConceptGateway/
 * InMemoryConceptStore, the same construction pattern
 * {@code ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest}'s WidgetDeleteFlowHarness uses to prove the
 * sibling delete-flow authz arm (NPDevGenerator's behaviorTest source set, which never runs against
 * an assembled app and so never touches this class or this gate's own coverage floor).
 */
class GeneratedServiceTenantRehydrationTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getByIdRehydratesTheRecordsOwnTenantId() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        UUID id = UUID.randomUUID();
        // Seeded directly through the store, bypassing the gateway/service -- mirrors the real write
        // path (DefaultConceptGateway.save -> store.save), which never puts a "tenantId" entry into
        // record.data() either; only the record's own dedicated component carries it.
        store.save(new ConceptRecord("ContactMessage", id.toString(), "tenant-a", Map.of(
                "name", "Ada", "email", "ada@example.com", "message", "Hi", "status", "NEW", "version", 0L
        )));

        ConfiguredConceptGatewaySemanticPolicy semanticPolicy = new ConfiguredConceptGatewaySemanticPolicy(List.of(
                new ConceptDefinition("ContactMessage", Map.of(), List.of(), null, Set.of(), new AccessRules(null, null))
        ));
        DefaultConceptGateway conceptGateway = new DefaultConceptGateway(
                store,
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                semanticPolicy,
                ConceptGatewayTraceSink.noop()
        );

        KernelRunner kernelRunner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowName -> Optional.empty(),
                (call, state) -> CapabilityResult.failure(
                        "CAPABILITY_DISPATCHER_NOT_CONFIGURED", "no capability dispatcher wired in this test",
                        CapabilityErrorKind.NOT_FOUND, Map.of()
                )
        );

        GeneratedCrudRuntimeSupport runtimeSupport =
                new GeneratedCrudRuntimeSupport(dummyCompiledModel(), kernelRunner).withConceptGateway(conceptGateway);

        // Real org.springframework.beans.factory.ObjectProvider is not a functional interface (unlike
        // the generator behaviorTest's own hand-stubbed one-method version), so these two providers --
        // untouched by a plain tenant-scoped read with no file fields -- are simply null here.
        ContactMessageServiceBase service = new ContactMessageServiceBase(
                runtimeSupport, conceptGateway, store, kernelRunner,
                Optional.empty(), null, null
        );

        bindRequestClaims("tenant-a", "tester", List.of("ADMIN", "USER"));
        Optional<ContactMessage> found = service.getById(id);

        assertTrue(found.isPresent(), "seeded record must be found");
        assertEquals("tenant-a", found.get().getTenantId(),
                "QUAL-45: entityFromRecord must rehydrate tenantId from the ConceptRecord, not leave it null");
    }

    private static void bindRequestClaims(String tenantId, String actorId, List<String> roles) {
        Map<String, Object> claims = Map.of("tenant_id", tenantId, "actor_id", actorId, "roles", roles);
        jakarta.servlet.http.HttpServletRequest request =
                (jakarta.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                        GeneratedServiceTenantRehydrationTest.class.getClassLoader(),
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

    private static CompiledModel dummyCompiledModel() {
        return new CompiledModel(
                "generated-service-tenant-rehydration-test", "1.0.0", "1.0.0",
                Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
