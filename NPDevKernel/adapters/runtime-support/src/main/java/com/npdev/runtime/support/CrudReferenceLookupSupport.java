package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.npdev.runtime.support.crud.bonds.BondNamingSupport.normalizeTenantForBondCheck;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.columnName;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.existsByIdSql;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.tableName;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.referenceValuesEqual;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toInteger;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toOffsetDateTime;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toUuid;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.mapWithStringKeys;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.readMapValue;

/**
 * CARVE-3: reference-target existence checks (SQL and in-memory ConceptGateway paths) and the
 * resource-conflict / scope-exists lookups used by the invariant engine, moved byte-faithfully out of
 * GeneratedCrudRuntimeSupport. The ConceptGateway is optional and set after construction, so it is
 * written through setConceptGateway.
 */
final class CrudReferenceLookupSupport {
    private final GeneratedCrudRuntimeSupport owner;
    private final EntityManager entityManager;
    // The lookup names CelInvariantEngine.Lookups uses, so an INVARIANT_UNCHECKABLE names the same one.
    private static final String CONFLICT_LOOKUP = "conflict";
    private static final String SCOPE_LOOKUP = "scope";

    private ConceptGateway conceptGateway;

    CrudReferenceLookupSupport(GeneratedCrudRuntimeSupport owner, EntityManager entityManager) {
        this.owner = owner;
        this.entityManager = entityManager;
    }

    void setConceptGateway(ConceptGateway conceptGateway) {
        this.conceptGateway = conceptGateway;
    }

    private Optional<CompiledConcept> findEntity(String name) {
        return owner.findEntity(name);
    }

    private CompiledConcept requireEntity(String entityName) {
        return owner.requireEntity(entityName);
    }

    private Optional<CompiledField> idField(CompiledConcept entity) {
        return owner.idField(entity);
    }

    private ExecutionContext resolveCurrentCrudContext() {
        return owner.resolveCurrentCrudContext();
    }

    private boolean existsById(CompiledConcept entity, UUID id) {
        if (entityManager == null || entity == null || id == null) {
            return false;
        }
        String table = tableName(entity);
        if (table.isBlank()) {
            return false;
        }
        Optional<CompiledField> idField = idField(entity);
        if (idField.isEmpty()) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(existsByIdSql(entity, idField.get()));
            query.setParameter("id", id.toString());
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean existsByAnchor(CompiledConcept entity, CompiledField anchor, Object value) {
        if (entity == null || anchor == null || value == null) {
            return false;
        }
        if (entityManager == null) {
            return existsByAnchorViaConceptGateway(entity, anchor, value);
        }
        String table = tableName(entity);
        String column = columnName(anchor);
        if (table.isBlank() || column.isBlank()) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT 1 FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = :value"
            );
            query.setParameter("value", String.valueOf(value));
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    // Reference-existence fallback for npdev.storage.mode=in-memory, where there is no
    // EntityManager and the referenced concept lives only in the ConceptGateway/ConceptStore.
    private boolean existsByAnchorViaConceptGateway(CompiledConcept entity, CompiledField anchor, Object value) {
        return existsByAnchorViaConceptGateway(entity, anchor, value, null);
    }

    // explicitContext: the caller's context when it has one (enforceBondTargetTenant), so the
    // tenant-keyed store read is scoped to THAT tenant; null falls back to the current CRUD context.
    boolean existsByAnchorViaConceptGateway(
            CompiledConcept entity,
            CompiledField anchor,
            Object value,
            ExecutionContext explicitContext
    ) {
        if (conceptGateway == null) {
            return false;
        }
        try {
            ExecutionContext context = explicitContext != null ? explicitContext : resolveCurrentCrudContext();
            String anchorName = anchor.getName();
            if ("id".equalsIgnoreCase(anchorName)) {
                return conceptGateway.read(
                        new ConceptReadRequest(entity.getName(), String.valueOf(value), context.tenantId()),
                        context
                ).isPresent();
            }
            return conceptGateway.list(new ConceptListRequest(entity.getName(), context.tenantId()), context)
                    .stream()
                    .anyMatch(record -> referenceValuesEqual(record.data().get(anchorName), value));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * One field of the stored row {@code id}, read through the ConceptGateway: the in-memory storage
     * mode has no EntityManager, so the row lives only in the ConceptStore (REG-275: the lifecycle
     * transition check needs the row's CURRENT status and silently skipped itself without one).
     * Empty when there is no gateway, no such row, or the read fails.
     */
    Optional<Object> readFieldViaConceptGateway(CompiledConcept entity, UUID id, String fieldName) {
        if (conceptGateway == null || entity == null || id == null || fieldName == null) {
            return Optional.empty();
        }
        try {
            ExecutionContext context = resolveCurrentCrudContext();
            return conceptGateway.read(new ConceptReadRequest(entity.getName(), id.toString(), context.tenantId()), context)
                    .map(record -> record.data().get(fieldName));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * REG-287: backs a REST write's conflicts()/overlapsProvider() rule. Searches only the caller's
     * tenant -- a booking in another tenant must not block this tenant's slot -- and refuses with
     * {@link CelInvariantEngine.LookupUnavailableException} (INVARIANT_UNCHECKABLE) when the rule names
     * an unknown concept or field or the query fails, instead of answering "no conflict". A payload
     * missing the resource, start or duration has nothing to collide with and is still "no conflict".
     */
    boolean resourceHasConflict(
            String entityName,
            String resourceFieldPath,
            Object resourceIdValue,
            String startsAtFieldPath,
            Object scheduledAtValue,
            String durationMinutesFieldPath,
            Object durationMinutesValue,
            Object excludeIdValue,
            Object payload
    ) {
        CompiledConcept entity = requireLookupEntity(CONFLICT_LOOKUP, entityName);
        requireLookupField(CONFLICT_LOOKUP, entity, resourceFieldPath);
        requireLookupField(CONFLICT_LOOKUP, entity, startsAtFieldPath);
        requireLookupField(CONFLICT_LOOKUP, entity, durationMinutesFieldPath);

        Object effectiveExcludeId = resolveConflictExcludeId(excludeIdValue, payload);
        UUID resourceId = toUuid(resourceIdValue);
        OffsetDateTime start = toOffsetDateTime(scheduledAtValue);
        Integer durationMinutes = toInteger(durationMinutesValue);
        UUID excludeId = toUuid(effectiveExcludeId);
        if (resourceId == null || start == null || durationMinutes == null || durationMinutes <= 0) {
            return false;
        }
        OffsetDateTime newEnd = start.plusMinutes(durationMinutes);
        if (entityManager == null) {
            return resourceHasConflictViaConceptGateway(
                    entity, resourceFieldPath, resourceId, startsAtFieldPath, start, durationMinutesFieldPath, newEnd, excludeId);
        }

        String table = tableName(entity);
        Optional<CompiledField> idField = idField(entity);
        if (table.isBlank() || idField.isEmpty()) {
            throw unavailable(CONFLICT_LOOKUP, "entity " + entityName + " has no table or id column to search");
        }
        String resourceColumn = columnName(entity, resourceFieldPath);
        String startsAtColumn = columnName(entity, startsAtFieldPath);
        String durationColumn = columnName(entity, durationMinutesFieldPath);
        String idColumn = columnName(idField.get());

        List<?> rows;
        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT " + idColumn + ", " + startsAtColumn + ", " + durationColumn + " "
                            + "FROM " + table + " "
                            + "WHERE CAST(" + resourceColumn + " AS VARCHAR) = :resourceId "
                            + "  AND tenant_id = :tenantId "
                            + "  AND (:excludeId = '' OR CAST(" + idColumn + " AS VARCHAR) <> :excludeId)"
            );
            query.setParameter("resourceId", resourceId.toString());
            query.setParameter("tenantId", callerTenant());
            query.setParameter("excludeId", excludeId == null ? "" : excludeId.toString());
            rows = query.getResultList();
        } catch (RuntimeException failure) {
            throw unavailable(CONFLICT_LOOKUP, "searching " + table + " failed: " + failure.getMessage());
        }
        if (rows == null) {
            return false;
        }
        for (Object row : rows) {
            if (!(row instanceof Object[] values) || values.length < 3) {
                continue;
            }
            if (overlaps(start, newEnd, values[1], values[2])) {
                return true;
            }
        }
        return false;
    }

    // Conflict-detection fallback for npdev.storage.mode=in-memory, where there is no
    // EntityManager and the resource's existing bookings live only in the ConceptGateway/ConceptStore.
    private boolean resourceHasConflictViaConceptGateway(
            CompiledConcept entity,
            String resourceFieldPath,
            UUID resourceId,
            String startsAtFieldPath,
            OffsetDateTime start,
            String durationMinutesFieldPath,
            OffsetDateTime newEnd,
            UUID excludeId
    ) {
        return listViaConceptGateway(CONFLICT_LOOKUP, entity).stream().anyMatch(record -> {
            Map<String, Object> data = record.data();
            if (!referenceValuesEqual(data.get(resourceFieldPath), resourceId.toString())) {
                return false;
            }
            UUID rowId = toUuid(data.get("id"));
            if (excludeId != null && excludeId.equals(rowId)) {
                return false;
            }
            return overlaps(start, newEnd, data.get(startsAtFieldPath), data.get(durationMinutesFieldPath));
        });
    }

    private static boolean overlaps(OffsetDateTime start, OffsetDateTime newEnd, Object existingStartValue, Object existingDurationValue) {
        OffsetDateTime existingStart = toOffsetDateTime(existingStartValue);
        Integer existingDuration = toInteger(existingDurationValue);
        if (existingStart == null || existingDuration == null || existingDuration <= 0) {
            return false;
        }
        OffsetDateTime existingEnd = existingStart.plusMinutes(existingDuration);
        return start.isBefore(existingEnd) && existingStart.isBefore(newEnd);
    }

    /**
     * REG-287: backs a REST write's scope.exists(concept, field, value). Searches only the caller's
     * tenant -- another tenant's row must neither satisfy the rule nor reveal that it exists -- and
     * refuses with {@link CelInvariantEngine.LookupUnavailableException} when the concept or field is
     * unknown (a dotted path included) or the query fails, instead of answering "not found". A null or
     * blank expected value matches nothing and is still "not found".
     */
    boolean scopeExists(String conceptName, String fieldPath, Object expectedValue) {
        CompiledConcept entity = requireLookupEntity(SCOPE_LOOKUP, conceptName);
        String field = fieldPath == null ? "" : fieldPath.trim();
        requireLookupField(SCOPE_LOOKUP, entity, field);
        String expectedText = expectedValue == null ? "" : String.valueOf(expectedValue).trim();
        if (expectedText.isEmpty()) {
            return false;
        }
        if (entityManager == null) {
            return listViaConceptGateway(SCOPE_LOOKUP, entity).stream()
                    .anyMatch(record -> referenceValuesEqual(record.data().get(field), expectedText));
        }

        String table = tableName(entity);
        if (table.isBlank()) {
            throw unavailable(SCOPE_LOOKUP, "entity " + conceptName + " has no table to search");
        }
        String column = columnName(entity, field);
        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT 1 FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = :expectedValue AND tenant_id = :tenantId"
            );
            query.setParameter("expectedValue", expectedText);
            query.setParameter("tenantId", callerTenant());
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (RuntimeException failure) {
            throw unavailable(SCOPE_LOOKUP, "searching " + table + " failed: " + failure.getMessage());
        }
    }

    // The tenant the generated service writes into tenant_id: the caller's, "default" when there is none.
    private String callerTenant() {
        return normalizeTenantForBondCheck(resolveCurrentCrudContext().tenantId());
    }

    private CompiledConcept requireLookupEntity(String lookup, String entityName) {
        if (entityName == null || entityName.isBlank()) {
            throw unavailable(lookup, "the rule names no entity");
        }
        return findEntity(entityName)
                .orElseThrow(() -> unavailable(lookup, "unknown entity " + entityName));
    }

    private static void requireLookupField(String lookup, CompiledConcept entity, String fieldName) {
        boolean declared = fieldName != null && !fieldName.isBlank() && entity.getFields().stream()
                .anyMatch(field -> field != null && fieldName.trim().equalsIgnoreCase(field.getName()));
        if (!declared) {
            throw unavailable(lookup, "entity " + entity.getName() + " has no field '" + fieldName + "'");
        }
    }

    private List<ConceptRecord> listViaConceptGateway(String lookup, CompiledConcept entity) {
        if (conceptGateway == null) {
            throw unavailable(lookup, "there is neither an EntityManager nor a ConceptGateway to search");
        }
        try {
            ExecutionContext context = resolveCurrentCrudContext();
            return conceptGateway.list(new ConceptListRequest(entity.getName(), context.tenantId()), context);
        } catch (RuntimeException failure) {
            throw unavailable(lookup, "listing " + entity.getName() + " failed: " + failure.getMessage());
        }
    }

    private static CelInvariantEngine.LookupUnavailableException unavailable(String lookup, String reason) {
        return new CelInvariantEngine.LookupUnavailableException(lookup, reason);
    }

    private Object resolveConflictExcludeId(Object excludeIdValue, Object payload) {
        UUID explicit = toUuid(excludeIdValue);
        if (explicit != null) {
            return explicit;
        }
        if (!(payload instanceof Map<?, ?> rawMap)) {
            return excludeIdValue;
        }
        Map<String, Object> map = mapWithStringKeys(rawMap);
        Object fromId = readMapValue(map, "id");
        UUID id = toUuid(fromId);
        if (id != null) {
            return id;
        }
        Object fromShadowId = readMapValue(map, "__id");
        UUID shadowId = toUuid(fromShadowId);
        if (shadowId != null) {
            return shadowId;
        }
        return excludeIdValue;
    }
}
