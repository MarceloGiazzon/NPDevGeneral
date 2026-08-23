package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.security.PermissionDecision;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "postgres"})
@Testcontainers
@Tag("integration")
public abstract class AbstractScenarioIntegrationTest {
    protected static final String INTEGRATION_TENANT = "dev";

    @Autowired
    protected ConceptStore conceptStore;

    @Autowired
    protected ObjectMapper integrationObjectMapper;

    @DynamicPropertySource
    static void registerScenarioDefaults(DynamicPropertyRegistry registry) {
        registry.add("npdev.auth.enabled", () -> "true");
        registry.add("npdev.auth.mode", () -> "apikey");
        registry.add(
                "npdev.auth.api-keys",
                () -> "dev-key=dev:developer:ADMIN|DEBUG|USER;"
                        + "tenant-a-key=tenant-a:actor-a:ADMIN|USER;"
                        + "tenant-b-key=tenant-b:actor-b:ADMIN|USER"
        );
        // A model resolving to the InMemory engine (canonical-demo does) bakes
        // spring.autoconfigure.exclude=...DataSourceAutoConfiguration,HibernateJpaAutoConfiguration,
        // JpaRepositoriesAutoConfiguration,FlywayAutoConfiguration into the generated
        // application-npdev-db.properties, loaded unconditionally via spring.config.import.
        // application-postgres.yml's own `spring.autoconfigure.exclude: []` cannot clear this --
        // spring.config.import gives the IMPORTED file higher precedence than the profile document
        // that declares the import, so the imported exclusion always wins over that YAML-level
        // attempt. A @DynamicPropertySource value, by contrast, is added to a property source placed
        // ahead of everything else in the environment, so it genuinely does override the import here
        // (unlike spring.main.allow-bean-definition-overriding above, which Spring Boot reads too
        // early in context preparation for a dynamic property to reach in time -- a different
        // property, read at a different point in the bootstrap sequence, with different rules).
        // Without this, every Postgres-profile test fails at StartupValidator with "DataSource bean
        // is required when mode=postgres" -- a real, accurate message for what is actually null here.
        registry.add("spring.autoconfigure.exclude", () -> "");
        // npdev.storage.mode is deliberately NOT set here. NpdevPluginConfig's persistence-capability
        // selection (InMemoryPersistenceCapabilityAdapter vs PostgresPersistenceCapabilityAdapter for
        // every GENERATED business-concept CRUD create/read) and NpdevRuntimeModeConfig's ConceptStore
        // bean both key off it, completely separately from the schema/DDL layer
        // npdev.trial.force-physical-schema (SchemaLifecycleExecutor) controls -- force-physical-schema
        // makes the SCHEMA look real (tables genuinely exist in Postgres), but without ALSO forcing
        // this property every business CRUD write still lands in the InMemory adapter's own store,
        // never reaching those tables (confirmed live: a direct `SELECT COUNT(*) FROM patients`
        // immediately after a 201-Created patient POST returned 0 rows).
        //
        // Three mechanisms were tried to set a shared "jdbc" default here with a single carve-out for
        // AsyncWaitResumeE2EIT (whose narrow compiled-model-path override has no real table for its
        // "User" concept, so it needs the app's own baked-in "in-memory" instead): (1) a same-key
        // registration in AsyncWaitResumeE2EIT's own @DynamicPropertySource method -- measured live,
        // silently ignored, the base class's registration for the same key always wins regardless of
        // method order; (2) a shared mutable static field read lazily through the supplier -- WORSE,
        // measured live: whichever test class happens to load first in a full-suite JVM run
        // permanently flips the field for every class that runs after it, so CanonicalDemoBusinessE2EIT
        // failed with an empty "patients" table only when run after AsyncWaitResumeE2EIT in the same
        // suite, never in isolation; (3) branching on the `Class<?> testClass` parameter some Spring
        // versions pass to a @DynamicPropertySource method -- rejected outright at context-bootstrap
        // time here ("must accept a single DynamicPropertyRegistry argument"), and even if it had been
        // accepted, a class-literal reference to AsyncWaitResumeE2EIT already failed to COMPILE from
        // this file with "cannot find symbol": this file is part of the "test" source set's own
        // compile task, which does not see AsyncWaitResumeE2EIT.java at all (it belongs to whatever
        // narrower/separate compilation backs the "integration"-tagged classes), even though both
        // classes live in the same package and directory.
        //
        // So the value is set per-CONCRETE-class instead, each in its own @DynamicPropertySource
        // method: "jdbc" wherever the test exercises canonical-demo's real business tables, omitted
        // (green-fielding the app's own default) in AsyncWaitResumeE2EIT alone. See that class's own
        // comment for the reasoning; this file makes no assumption about it either way.
    }

    protected void deleteAllConceptRows(String... conceptNames) {
        for (String conceptName : conceptNames) {
            List<ConceptRecord> rows = conceptStore.findAll(INTEGRATION_TENANT, conceptName);
            for (ConceptRecord row : rows) {
                conceptStore.deleteById(INTEGRATION_TENANT, conceptName, row.id());
            }
        }
    }

    protected List<ConceptRecord> conceptRows(String conceptName) {
        return conceptStore.findAll(INTEGRATION_TENANT, conceptName);
    }

    protected <T> T conceptEntity(ConceptRecord record, Class<T> entityType) {
        Map<String, Object> data = new LinkedHashMap<>(record.data());
        data.putIfAbsent("id", record.id());
        return integrationObjectMapper.convertValue(data, entityType);
    }

    // The Testcontainers JDBC URL in application-postgres.yml manages lifecycle.

    @TestConfiguration
    static class RuntimeHostIntegrationPermissionConfig {
        // Named differently from NpdevCapabilityBindingConfig#permissionEvaluator (the production
        // bean) rather than sharing its name: a same-named @Bean method needs
        // spring.main.allow-bean-definition-overriding, a GLOBAL context-wide flag that affects every
        // bean in the context, not just this one. @Primary resolves the by-type autowiring every real
        // consumer uses without touching that flag at all, so only this one bean is affected. Nothing
        // in the codebase looks this bean up by its production name (verified via grep for
        // getBean("permissionEvaluator") / @Qualifier("permissionEvaluator")).
        @Bean
        @Primary
        PermissionEvaluator integrationTestPermissionEvaluator() {
            return (subject, requirement) -> subject.roles().contains("admin")
                    ? PermissionDecision.allow("integration_admin_role")
                    : PermissionDecision.deny("integration_permission_denied", "Permission denied by integration test policy");
        }
    }
}
