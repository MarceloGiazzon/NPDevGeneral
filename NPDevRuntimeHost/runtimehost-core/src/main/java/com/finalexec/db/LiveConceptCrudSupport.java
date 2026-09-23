package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * REG-244 Phase 4C: lets a concept added to the model AFTER generation (provisioned live by
 * {@link NewConceptSchemaProvisioner} via {@code /model-reload}) become REST-reachable through
 * {@code GeneratedConceptCrudController} with no regenerate/rebuild/restart. The generated
 * controller's own {@code concepts} map is built once at generation time and stays untouched; this
 * class is the FALLBACK the controller consults on a miss, resolving directly against the live
 * {@link CompiledModel} and the standing {@link ConceptGateway} bean -- already {@code
 * ModelHolder}-aware and schema-live in its production JDBC store ({@link JdbcBusinessConceptStore}),
 * so no new persistence code was needed to make this work.
 *
 * <p>A plain, non-templated class (unlike the controller it feeds) so it is directly unit-testable
 * the same way {@code ConceptQueryControllerExportCsvVolumeTest} tests {@code ConceptQueryController}
 * -- construct a real {@link CompiledModel} + H2-backed {@code JdbcBusinessConceptStore} +
 * {@code DefaultConceptGateway}, no Spring context or generated app required.
 */
public final class LiveConceptCrudSupport {

    private LiveConceptCrudSupport() {
    }

    public record FieldInfo(String name, String columnName, boolean required, boolean id) {
    }

    public record LiveConceptOps(
            String conceptName,
            String route,
            String tableName,
            String idField,
            List<FieldInfo> fields,
            Supplier<ConceptListSlice<ConceptRecord>> list,
            Function<UUID, Optional<ConceptRecord>> getById,
            Function<Map<String, Object>, ConceptRecord> create,
            BiFunction<UUID, Map<String, Object>, Optional<ConceptRecord>> update,
            Predicate<UUID> delete,
            Function<ConceptQuery, ConceptPage> page
    ) {
    }

    /**
     * Matches {@code requestedRoute} against the live model's concepts using the EXACT SAME route
     * derivation the generated controller's own baked {@code {{route}}} template variable uses
     * ({@link SqlIdentifierSupport#aliasPreservingTableName}), and the EXACT SAME eligibility check
     * {@link NewConceptSchemaProvisioner} uses to decide whether a concept gets a table -- a concept
     * only gets a live REST binding if it is exactly the shape that class would provision a table
     * for, so "has a table" and "is CRUD-reachable" can never drift apart.
     */
    public static Optional<LiveConceptOps> resolve(
            CompiledModel liveModel, String requestedRoute, ConceptGateway gateway, ExecutionContext context
    ) {
        for (CompiledConcept concept : liveModel.getConcepts()) {
            String route = SqlIdentifierSupport.aliasPreservingTableName(concept, liveModel.getContexts());
            if (route.equals(requestedRoute) && NewConceptSchemaProvisioner.outOfScopeReason(concept) == null) {
                return Optional.of(buildOps(concept, route, gateway, context));
            }
        }
        return Optional.empty();
    }

    private static LiveConceptOps buildOps(
            CompiledConcept concept, String route, ConceptGateway gateway, ExecutionContext context
    ) {
        String name = concept.getName();
        List<FieldInfo> fields = new ArrayList<>();
        String idField = "id";
        for (CompiledField field : concept.getFields()) {
            fields.add(new FieldInfo(field.getName(), SqlIdentifierSupport.columnName(field),
                    field.isRequired(), field.isId()));
            if (field.isId()) {
                idField = field.getName();
            }
        }
        String resolvedIdField = idField;

        return new LiveConceptOps(
                name, route, SqlIdentifierSupport.tableName(concept), resolvedIdField, List.copyOf(fields),
                () -> gateway.listCapped(new ConceptListRequest(name, null), context, ConceptQuery.MAX_LIMIT),
                id -> gateway.read(new ConceptReadRequest(name, id.toString(), null), context),
                data -> {
                    String newId = UUID.randomUUID().toString();
                    // The store's save() echoes back whatever data map it was given (no re-SELECT) --
                    // unlike an update (whose merge starts from a freshly-read record that already has
                    // its id), a create's caller-supplied body never carries the generated id, so it
                    // must be folded in here or the response (and the very next getById) would see a
                    // record with no id in its data.
                    Map<String, Object> withId = new LinkedHashMap<>(data);
                    withId.put(resolvedIdField, newId);
                    return gateway.save(new ConceptWriteRequest(name, newId, null, withId), context);
                },
                (id, partial) -> {
                    Optional<ConceptRecord> existing = gateway.read(
                            new ConceptReadRequest(name, id.toString(), null), context);
                    if (existing.isEmpty()) {
                        return Optional.empty();
                    }
                    // Partial merge, not full replace: the generated controller's own batch setField
                    // path already calls update() with a SINGLETON one-field map, so a full replace
                    // here would silently null out every other column on a bulk field edit.
                    Map<String, Object> merged = new LinkedHashMap<>(existing.get().data());
                    merged.putAll(partial);
                    return Optional.of(gateway.save(
                            new ConceptWriteRequest(name, id.toString(), null, merged), context));
                },
                id -> {
                    ConceptReadRequest readRequest = new ConceptReadRequest(name, id.toString(), null);
                    boolean found = gateway.read(readRequest, context).isPresent();
                    if (found) {
                        gateway.delete(readRequest, context);
                    }
                    return found;
                },
                query -> gateway.query(new ConceptQueryRequest(name, null, query), context)
        );
    }
}
