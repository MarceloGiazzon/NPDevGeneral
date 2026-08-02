package com.npdev.kernel.properties;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGateways;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.AuditLogStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RC-A3 (Move 14 Phase B item B2): {@link DefaultPropertyResolver} against the 15 cascade vectors in
 * {@code __OutsideRepo/move13-helpers/rc-a3-cascade-vectors.json} -- the exact fixture (model,
 * context, rows) reproduced here so each vector is a literal, traceable test. Vectors 7-9 are written
 * FIRST, per that file's own instruction: they pin the single rule a naive implementation gets
 * wrong -- ROW PRESENCE is the is-set signal, not "propValue is non-null."
 */
class PropertyResolverCascadeTest {

    // ---- modelFixture: propertyScopes (most specific first) ----------------------------------
    private static final CompiledPropertyScope USER_SCOPE = new CompiledPropertyScope("user", "$user.id");
    private static final CompiledPropertyScope ESTAB_SCOPE = new CompiledPropertyScope("estabelecimento", "$user.estabelecimentoId");
    private static final CompiledPropertyScope TENANT_SCOPE = new CompiledPropertyScope("tenant", null);

    // ---- modelFixture: properties ------------------------------------------------------------
    private static final CompiledProperty PAGE_ROWS =
            new CompiledProperty("pageRows", "int", 25, List.of("tenant", "user"), null, false);
    private static final CompiledProperty DATE_FORMAT =
            new CompiledProperty("dateFormat", "string", "dd/MM/yyyy", List.of("tenant", "user"), null, false);
    private static final CompiledProperty DOBRAR_CONF =
            new CompiledProperty("dobrarConf", "boolean", true, List.of("tenant", "estabelecimento"), null, true);
    private static final CompiledProperty SEM_DEFAULT =
            new CompiledProperty("semDefault", "string", null, List.of("tenant"), null, false);

    // ---- context: tenantId=T1, userId=U1, estabelecimentoId=E1 -------------------------------
    // NOTE: ExecutionContext canonicalizes tenantId to lowercase (REG-25, the isolation-bucket key
    // must not have two casings of the same tenant) but leaves actorId/tags as-authored -- so "t1"
    // here (not "T1") is what context.tenantId() actually returns, and every seeded row's tenantId
    // must match it exactly for the in-memory store's tenant-scoped lookup to find it.
    private static final ExecutionContext CTX = ExecutionContext.of("t1", "U1").withTag("estabelecimentoId", "E1");

    private static CompiledModel model() {
        return new CompiledModel(
                "wms.props", "1.0.0", "1.0",
                Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(),
                List.of(USER_SCOPE, ESTAB_SCOPE, TENANT_SCOPE),
                List.of(PAGE_ROWS, DATE_FORMAT, DOBRAR_CONF, SEM_DEFAULT)
        );
    }

    private static PropertyResolver resolver() {
        return new DefaultPropertyResolver(ConceptGateways.inMemory(), AuditLogStore.noop(), model());
    }

    /** Seeds a workspace::PropertyValue row directly, bypassing set()'s own settableAt enforcement --
     *  these tests are about resolve()/explain() reading rows, not about how they got written. */
    private static void seedRow(ConceptGateway gateway, String tenantId, String scopeType, String scopeId,
                                 String propKey, String propValue) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id);
        data.put("scopeType", scopeType);
        data.put("scopeId", scopeId);
        data.put("propKey", propKey);
        data.put("propValue", propValue);
        gateway.save(new ConceptWriteRequest("workspace::PropertyValue", id, tenantId, data), ExecutionContext.of(tenantId, "seed"));
    }

    // =========================================================================================
    // Vectors 7-9 FIRST: the bug every naive cascade ships with.
    // =========================================================================================

    @Test
    void vector7_explicitNullAtUserScopeBeatsNonNullTenantValue() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "tenant", "t1", "dateFormat", "yyyy-MM-dd");
        seedRow(gateway, "t1", "user", "U1", "dateFormat", null);
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("dateFormat", CTX);
        assertNull(explanation.value(), "a present row with a NULL value is SET, not absent");
        assertEquals(new PropertyExplanation.ScopeRef("user", "U1"), explanation.source());
        assertEquals(List.of("tenant", "default"), scopeTypesOf(explanation));
    }

    @Test
    void vector8_overrideATrueDefaultBackToFalseAtALowerScope() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "estabelecimento", "E1", "dobrarConf", "false");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("dobrarConf", CTX);
        assertEquals(Boolean.FALSE, explanation.value(), "a falsy value must not be mistaken for 'unset'");
        assertEquals(new PropertyExplanation.ScopeRef("estabelecimento", "E1"), explanation.source());
        assertEquals(List.of("default"), scopeTypesOf(explanation));
    }

    @Test
    void vector9_emptyStringIsAValueNotAnAbsence() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "user", "U1", "dateFormat", "");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("dateFormat", CTX);
        assertEquals("", explanation.value());
        assertEquals(new PropertyExplanation.ScopeRef("user", "U1"), explanation.source());
        assertEquals(List.of("default"), scopeTypesOf(explanation));
    }

    // =========================================================================================
    // Vectors 1-6
    // =========================================================================================

    @Test
    void vector1_fallsThroughEveryScopeToTheModelDefault() {
        PropertyExplanation explanation = resolver().explain("pageRows", CTX);
        assertEquals(25, explanation.value());
        assertEquals(PropertyExplanation.ScopeRef.DEFAULT, explanation.source());
        assertTrue(explanation.overrode().isEmpty());
    }

    @Test
    void vector2_tenantBeatsDefault() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "tenant", "t1", "pageRows", "50");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("pageRows", CTX);
        assertEquals(50, explanation.value());
        assertEquals(new PropertyExplanation.ScopeRef("tenant", "t1"), explanation.source());
        assertEquals(List.of("default"), scopeTypesOf(explanation));
    }

    @Test
    void vector3_userBeatsTenantBeatsDefault() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "tenant", "t1", "pageRows", "50");
        seedRow(gateway, "t1", "user", "U1", "pageRows", "10");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("pageRows", CTX);
        assertEquals(10, explanation.value());
        assertEquals(new PropertyExplanation.ScopeRef("user", "U1"), explanation.source());
        assertEquals(List.of("tenant", "default"), scopeTypesOf(explanation));
    }

    @Test
    void vector4_middleScopeWinsWhenTheMostSpecificIsAbsent() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "tenant", "t1", "dobrarConf", "false");
        seedRow(gateway, "t1", "estabelecimento", "E1", "dobrarConf", "true");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("dobrarConf", CTX);
        assertEquals(Boolean.TRUE, explanation.value());
        assertEquals(new PropertyExplanation.ScopeRef("estabelecimento", "E1"), explanation.source());
        assertEquals(List.of("tenant", "default"), scopeTypesOf(explanation));
    }

    @Test
    void vector5_anotherUsersRowMustNotLeak() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t1", "user", "U2", "pageRows", "999");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("pageRows", CTX);
        assertEquals(25, explanation.value(), "scopeId must be matched, not just scopeType");
        assertEquals(PropertyExplanation.ScopeRef.DEFAULT, explanation.source());
    }

    @Test
    void vector6_anotherTenantsRowMustNotLeak() {
        ConceptGateway gateway = ConceptGateways.inMemory();
        seedRow(gateway, "t2", "tenant", "t2", "pageRows", "999");
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        PropertyExplanation explanation = resolver.explain("pageRows", CTX);
        assertEquals(25, explanation.value(), "every stored value is tenant-scoped, as every concept table already is");
        assertEquals(PropertyExplanation.ScopeRef.DEFAULT, explanation.source());
    }

    // =========================================================================================
    // Vector 10: distinguishes "no value anywhere" from vector 7's "explicitly set to null."
    // =========================================================================================

    @Test
    void vector10_declaredDefaultOfNullNothingStored() {
        PropertyExplanation explanation = resolver().explain("semDefault", CTX);
        assertNull(explanation.value());
        assertEquals(PropertyExplanation.ScopeRef.DEFAULT, explanation.source(),
                "must report a DIFFERENT source than vector 7's null (which came from the user scope)");
        assertTrue(explanation.overrode().isEmpty());
    }

    // =========================================================================================
    // Vectors 11-12: errors vs. skips
    // =========================================================================================

    @Test
    void vector11_unknownPropertyNameIsAnErrorNotANull() {
        PropertyResolver resolver = resolver();
        PropertyNotDeclaredException thrown = assertThrows(PropertyNotDeclaredException.class,
                () -> resolver.resolve("naoExiste", CTX));
        assertEquals("PROPERTY_NOT_DECLARED", thrown.code());
    }

    @Test
    void vector12_aScopeTheContextCannotSupplySkipsRatherThanThrows() {
        ExecutionContext contextWithoutEstabelecimento = ExecutionContext.of("t1", "U1");
        // No estabelecimentoId tag at all -- resolution must SKIP that scope, not throw.
        PropertyExplanation explanation = resolver().explain("pageRows", contextWithoutEstabelecimento);
        assertEquals(25, explanation.value());
        assertEquals(PropertyExplanation.ScopeRef.DEFAULT, explanation.source());
    }

    // =========================================================================================
    // Vectors 13-14: write path -- settableAt enforcement + audit.
    // =========================================================================================

    @Test
    void vector13_settableAtIsEnforcedOnWriteNotOnRead() {
        PropertyResolver resolver = resolver();
        PropertyNotSettableAtScopeException thrown = assertThrows(PropertyNotSettableAtScopeException.class,
                () -> resolver.set("user", "U1", "dobrarConf", "false", CTX));
        assertEquals("PROPERTY_NOT_SETTABLE_AT_SCOPE", thrown.code());
    }

    @Test
    void vector14_everySetIsAuditedIncludingASetToNull() {
        RecordingAuditLogStore audit = new RecordingAuditLogStore();
        PropertyResolver resolver = new DefaultPropertyResolver(ConceptGateways.inMemory(), audit, model());

        resolver.set("tenant", "t1", "dateFormat", null, CTX);

        assertEquals(1, audit.records.size());
        var record = audit.records.get(0);
        assertEquals("U1", record.actorId());
        assertEquals("tenant:t1:dateFormat", record.resourceId());
        assertEquals("true", record.meta().get("newValueNull"));
        assertEquals("false", record.meta().get("oldValuePresent"), "no prior row existed");
    }

    private static List<String> scopeTypesOf(PropertyExplanation explanation) {
        return explanation.overrode().stream().map(PropertyExplanation.OverriddenScope::scopeType).toList();
    }

    private static final class RecordingAuditLogStore implements AuditLogStore {
        private final List<com.npdev.kernel.audit.AuditRecord> records = new java.util.ArrayList<>();

        @Override
        public void append(com.npdev.kernel.audit.AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<com.npdev.kernel.audit.AuditRecord> search(com.npdev.kernel.ports.AuditQuery query) {
            return List.copyOf(records);
        }
    }
}
