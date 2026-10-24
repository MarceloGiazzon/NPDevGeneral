package com.npdev.kernel.properties;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.AuditLogStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REG-306: an app that did not compose {@code workspace::PropertyValue} (internal.tables off) is
 * served by a JDBC store that REFUSES the unknown concept with an IllegalArgumentException, where the
 * in-memory store answers an empty page. Resolving a declared property must fall through to its default
 * on both; only a write still fails.
 */
class PropertyResolverUnknownConceptTest {

    private static final CompiledPropertyScope TENANT_SCOPE = new CompiledPropertyScope("tenant", null);
    private static final CompiledProperty PAGE_ROWS =
            new CompiledProperty("pageRows", "int", 25, List.of("tenant"), null, false);
    private static final ExecutionContext CTX = ExecutionContext.of("t1", "U1");

    /** No concepts at all: PropertyValue is not composed into this model. */
    private static CompiledModel modelWithoutPropertyValueConcept() {
        return new CompiledModel(
                "wms.props", "1.0.0", "1.0",
                Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(),
                List.of(TENANT_SCOPE),
                List.of(PAGE_ROWS)
        );
    }

    private static PropertyResolver resolver() {
        CompiledModel model = modelWithoutPropertyValueConcept();
        return new DefaultPropertyResolver(new UnknownConceptGateway(), AuditLogStore.noop(), () -> model);
    }

    @Test
    void readFallsBackToTheDeclaredDefaultWhenTheStoreDoesNotKnowThePropertyValueConcept() {
        PropertyExplanation explanation = resolver().explain("pageRows", CTX);

        assertEquals(25, explanation.value());
        assertEquals(PropertyExplanation.ScopeRef.DEFAULT, explanation.source());
        assertEquals(25, resolver().resolve("pageRows", CTX));
    }

    @Test
    void writeStillFailsForAnUndeclaredConcept() {
        PropertyResolver resolver = resolver();

        assertThrows(IllegalArgumentException.class,
                () -> resolver.set("tenant", "t1", "pageRows", "50", CTX));
    }

    /** What the JDBC ConceptStore does for a concept the app never composed. */
    private static final class UnknownConceptGateway implements ConceptGateway {
        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            throw unknown(request.conceptName());
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            throw unknown(request.conceptName());
        }

        @Override
        public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
            throw unknown(request.conceptName());
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            throw unknown(request.conceptName());
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            throw unknown(request.conceptName());
        }

        private static IllegalArgumentException unknown(String conceptName) {
            return new IllegalArgumentException("Unknown concept: " + conceptName);
        }
    }
}
