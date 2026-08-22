package com.npdev.adapters.persistence.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.PersistenceCapabilityContract;
import com.npdev.kernel.ports.TenantScope;
import com.npdev.kernel.ports.TenantScopedPersistenceCapabilityContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of the persistence capability contract for in-proc runtime mode.
 */
public final class InMemoryPersistenceCapabilityAdapter
        implements PersistenceCapabilityContract, TenantScopedPersistenceCapabilityContract {
    private final Map<String, Map<Object, Map<String, Object>>> storeByConcept = new ConcurrentHashMap<>();
    private final CompiledModel compiledModel;
    // REG-187: when a ConceptStore is provided (the runtime injects the SAME store the generated
    // service's CRUD reads), save() delegates to it, so a flow-driven create is visible to the
    // service's post-flow findById under InMemory. Null keeps the legacy isolated Map (unit tests /
    // non-runtime wiring).
    private final ConceptStore conceptStore;

    public InMemoryPersistenceCapabilityAdapter() {
        this(null);
    }

    // See PostgresPersistenceCapabilityAdapter's constructor note: flow-compiled createConcept/
    // updateConcept steps dispatch straight to save(), bypassing the ConceptGatewaySemanticPolicy
    // defaults pass generic CRUD create goes through. Without compiledModel, a flow create under
    // InMemory storage that omits a field with a declared default persisted it as null/missing
    // (ARCH-8b).
    public InMemoryPersistenceCapabilityAdapter(CompiledModel compiledModel) {
        this(compiledModel, null);
    }

    public InMemoryPersistenceCapabilityAdapter(CompiledModel compiledModel, ConceptStore conceptStore) {
        this.compiledModel = compiledModel;
        this.conceptStore = conceptStore;
    }

    @Override
    public Object save(Object entity) {
        return save("default", entity);
    }

    public Object save(Object concept, Object entity) {
        String conceptKey = normalizeConcept(concept);
        Map<String, Object> record = mutableRecord(entity);
        applyFieldDefaults(concept, record);
        String conceptIdField = inferredRuntimeIdField(concept);

        Object id = record.get("id");
        if (id == null && conceptIdField != null) {
            id = record.get(conceptIdField);
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        // The in-memory store is keyed on the canonical "id" field. Always expose it
        // so callers, findById, and delete all agree on the same key. Any concept-specific
        // id field the record arrived with (e.g. "userId") is read above but never used to
        // hide the canonical id.
        record.put("id", id);

        if (conceptStore != null) {
            // REG-187: write to the SAME ConceptStore the generated service's CRUD reads, so a
            // flow-driven create is visible to the service's post-flow findById under InMemory. The
            // tenant the kernel stamped into the entity (persistence.save tenant isolation) is what
            // the service's own read uses, so both sides agree.
            Object tenant = record.get("tenantId");
            if (tenant == null) {
                tenant = record.get("tenant_id");
            }
            String tenantId = (tenant == null || String.valueOf(tenant).isBlank())
                    ? "default" : String.valueOf(tenant);
            ConceptRecord saved = conceptStore.save(
                    new ConceptRecord(conceptKey, String.valueOf(id), tenantId, new LinkedHashMap<>(record)));
            return immutableRecord(new LinkedHashMap<>(saved.data()));
        }

        storeByConcept
                .computeIfAbsent(conceptKey, k -> new ConcurrentHashMap<>())
                .put(id, new LinkedHashMap<>(record));

        return immutableRecord(record);
    }


    // ---------------------------------------------------------------------------------------------
    // REG-46: the tenant-scoped port. The in-memory adapter had exactly the same hole as the Postgres
    // one -- which is why REG-46 was a gap in the PORT rather than a difference between backends -- so
    // it is closed here too. Dev and production must not disagree about who can read what, or an
    // isolation bug is invisible until deployment.
    //
    // A record with no tenant marker is visible to everyone, matching the Postgres adapter's rule of
    // only scoping tables that actually carry a tenant column.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Object save(TenantScope scope, Object entity) {
        Map<String, Object> record = mutableRecord(entity);
        record.put("tenantId", scope.tenantId());
        return save((Object) "default", (Object) record);
    }

    @Override
    public Object findById(TenantScope scope, Object concept, Object id) {
        Object found = findById(concept, id);
        return visibleTo(scope, found) ? found : null;
    }

    @Override
    public Object query(TenantScope scope, Object concept, Object criteria) {
        Object rows = query(concept, criteria);
        if (!(rows instanceof List<?> list)) {
            return rows;
        }
        List<Object> visible = new ArrayList<>();
        for (Object row : list) {
            if (visibleTo(scope, row)) {
                visible.add(row);
            }
        }
        return List.copyOf(visible);
    }

    @Override
    public Object delete(TenantScope scope, Object concept, Object id) {
        // Read first: deleting a row the caller cannot see must report "nothing deleted" rather than
        // deleting it, and must not disclose that it exists.
        if (!visibleTo(scope, findById(concept, id))) {
            return false;
        }
        return delete(concept, id);
    }

    @Override
    public Object exists(TenantScope scope, Object concept, Object field, Object value) {
        Object rows = query(scope, concept, Map.of(Objects.toString(field, ""), value));
        return rows instanceof List<?> list && !list.isEmpty();
    }

    @Override
    public Object unique(TenantScope scope, Object concept, Object field, Object value) {
        return !(Boolean) exists(scope, concept, field, value);
    }

    /** A record belongs to the caller when it carries no tenant marker, or carries theirs. */
    private static boolean visibleTo(TenantScope scope, Object record) {
        if (record == null) {
            return false;
        }
        if (!(record instanceof Map<?, ?> map)) {
            return true;
        }
        Object owner = map.get("tenantId");
        if (owner == null) {
            owner = map.get("tenant_id");
        }
        return scope.covers(owner == null ? null : String.valueOf(owner));
    }

    @Override
    public Object findById(Object concept, Object id) {
        if (id == null) {
            return null;
        }
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return null;
        }
        Map<String, Object> record = conceptStore.get(id);
        return record == null ? null : immutableRecord(record);
    }

    @Override
    public Object query(Object concept, Object criteria) {
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return List.of();
        }

        Map<String, Object> criteriaMap = criteriaMap(criteria);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> record : conceptStore.values()) {
            if (matchesCriteria(record, criteriaMap)) {
                out.add(immutableRecord(record));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Object delete(Object concept, Object id) {
        if (id == null) {
            return false;
        }
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return false;
        }
        return conceptStore.remove(id) != null;
    }

    @Override
    public Object exists(Object concept, Object field, Object value) {
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return false;
        }
        String fieldName = Objects.toString(field, "");
        for (Map<String, Object> record : conceptStore.values()) {
            Object candidate = record.get(fieldName);
            if (Objects.equals(candidate, value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object unique(Object concept, Object field, Object value) {
        boolean exists = (Boolean) exists(concept, field, value);
        return !exists;
    }

    // Scoped to literal defaultValue only, matching PostgresPersistenceCapabilityAdapter's pass --
    // defaultExpression (computed from other fields) is not evaluated here.
    private void applyFieldDefaults(Object concept, Map<String, Object> record) {
        if (compiledModel == null) {
            return;
        }
        String name = normalizeConcept(concept);
        CompiledConcept compiledConcept = null;
        for (CompiledConcept candidate : compiledModel.getConcepts()) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                compiledConcept = candidate;
                break;
            }
        }
        if (compiledConcept == null) {
            return;
        }
        for (CompiledField field : compiledConcept.getFields()) {
            if (field.getSchema() == null || field.getSchema().getDefaultValue() == null) {
                continue;
            }
            Object existing = record.get(field.getName());
            if (existing == null || (existing instanceof String text && text.isBlank())) {
                record.put(field.getName(), field.getSchema().getDefaultValue());
            }
        }
    }

    private static String normalizeConcept(Object concept) {
        String value = Objects.toString(concept, "default").trim().toLowerCase();
        return value.isBlank() ? "default" : value;
    }

    private static String inferredRuntimeIdField(Object concept) {
        String raw = Objects.toString(concept, "default").trim();
        if (raw.isBlank() || "default".equalsIgnoreCase(raw)) {
            return "id";
        }
        return raw.substring(0, 1).toLowerCase() + raw.substring(1) + "Id";
    }

    private static Map<String, Object> mutableRecord(Object entity) {
        if (!(entity instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Persistence save expects a map-like entity");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> criteriaMap(Object criteria) {
        if (criteria == null) {
            return Map.of();
        }
        if (!(criteria instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Query criteria must be a map");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static boolean matchesCriteria(Map<String, Object> record, Map<String, Object> criteria) {
        for (Map.Entry<String, Object> criterion : criteria.entrySet()) {
            if (!Objects.equals(record.get(criterion.getKey()), criterion.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> immutableRecord(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
