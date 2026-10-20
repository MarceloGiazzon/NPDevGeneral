package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        if (entityName == null
                || entityName.isBlank()
                || resourceFieldPath == null
                || resourceFieldPath.isBlank()
                || startsAtFieldPath == null
                || startsAtFieldPath.isBlank()
                || durationMinutesFieldPath == null
                || durationMinutesFieldPath.isBlank()) {
            return false;
        }
        if (entityManager == null) {
            return resourceHasConflictViaConceptGateway(
                    entityName,
                    resourceFieldPath,
                    resourceIdValue,
                    startsAtFieldPath,
                    scheduledAtValue,
                    durationMinutesFieldPath,
                    durationMinutesValue,
                    excludeIdValue,
                    payload
            );
        }

        Optional<CompiledConcept> entityOpt = findEntity(entityName);
        if (entityOpt.isEmpty()) {
            return false;
        }
        CompiledConcept entity = entityOpt.get();
        String table = tableName(entity);
        if (table.isBlank()) {
            return false;
        }

        String resourceColumn = columnName(entity, resourceFieldPath);
        String startsAtColumn = columnName(entity, startsAtFieldPath);
        String durationColumn = columnName(entity, durationMinutesFieldPath);
        Optional<CompiledField> idField = idField(entity);
        if (resourceColumn.isBlank() || startsAtColumn.isBlank() || durationColumn.isBlank() || idField.isEmpty()) {
            return false;
        }
        String idColumn = columnName(idField.get());

        Object effectiveExcludeId = resolveConflictExcludeId(excludeIdValue, payload);
        UUID resourceId = toUuid(resourceIdValue);
        OffsetDateTime start = toOffsetDateTime(scheduledAtValue);
        Integer durationMinutes = toInteger(durationMinutesValue);
        UUID excludeId = toUuid(effectiveExcludeId);
        if (resourceId == null || start == null || durationMinutes == null || durationMinutes <= 0) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT " + idColumn + ", " + startsAtColumn + ", " + durationColumn + " "
                            + "FROM " + table + " "
                            + "WHERE CAST(" + resourceColumn + " AS VARCHAR) = :resourceId "
                            + "  AND (:excludeId = '' OR CAST(" + idColumn + " AS VARCHAR) <> :excludeId)"
            );
            query.setParameter("resourceId", resourceId.toString());
            query.setParameter("excludeId", excludeId == null ? "" : excludeId.toString());
            List<?> rows = query.getResultList();
            OffsetDateTime newEnd = start.plusMinutes(durationMinutes);
            if (rows == null || rows.isEmpty()) {
                return false;
            }
            for (Object row : rows) {
                if (!(row instanceof Object[] values) || values.length < 3) {
                    continue;
                }
                OffsetDateTime existingStart = toOffsetDateTime(values[1]);
                Integer existingDuration = toInteger(values[2]);
                if (existingStart == null || existingDuration == null || existingDuration <= 0) {
                    continue;
                }
                OffsetDateTime existingEnd = existingStart.plusMinutes(existingDuration);
                if (start.isBefore(existingEnd) && existingStart.isBefore(newEnd)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    // Conflict-detection fallback for npdev.storage.mode=in-memory, where there is no
    // EntityManager and the resource's existing bookings live only in the ConceptGateway/ConceptStore.
    private boolean resourceHasConflictViaConceptGateway(
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
        if (conceptGateway == null) {
            return false;
        }
        Object effectiveExcludeId = resolveConflictExcludeId(excludeIdValue, payload);
        UUID resourceId = toUuid(resourceIdValue);
        OffsetDateTime start = toOffsetDateTime(scheduledAtValue);
        Integer durationMinutes = toInteger(durationMinutesValue);
        UUID excludeId = toUuid(effectiveExcludeId);
        if (resourceId == null || start == null || durationMinutes == null || durationMinutes <= 0) {
            return false;
        }
        OffsetDateTime newEnd = start.plusMinutes(durationMinutes);

        try {
            ExecutionContext context = resolveCurrentCrudContext();
            return conceptGateway.list(new ConceptListRequest(entityName, context.tenantId()), context)
                    .stream()
                    .anyMatch(record -> {
                        Map<String, Object> data = record.data();
                        if (!referenceValuesEqual(data.get(resourceFieldPath), resourceId.toString())) {
                            return false;
                        }
                        UUID rowId = toUuid(data.get("id"));
                        if (excludeId != null && excludeId.equals(rowId)) {
                            return false;
                        }
                        OffsetDateTime existingStart = toOffsetDateTime(data.get(startsAtFieldPath));
                        Integer existingDuration = toInteger(data.get(durationMinutesFieldPath));
                        if (existingStart == null || existingDuration == null || existingDuration <= 0) {
                            return false;
                        }
                        OffsetDateTime existingEnd = existingStart.plusMinutes(existingDuration);
                        return start.isBefore(existingEnd) && existingStart.isBefore(newEnd);
                    });
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean scopeExists(String conceptName, String fieldPath, Object expectedValue) {
        if (entityManager == null
                || conceptName == null
                || conceptName.isBlank()
                || fieldPath == null
                || fieldPath.isBlank()
                || expectedValue == null) {
            return false;
        }

        String trimmedFieldPath = fieldPath.trim();
        if (trimmedFieldPath.contains(".") || trimmedFieldPath.contains("[") || trimmedFieldPath.contains("]")) {
            return false;
        }

        Optional<CompiledConcept> entityOpt = findEntity(conceptName);
        if (entityOpt.isEmpty()) {
            return false;
        }

        CompiledConcept entity = entityOpt.get();
        String table = tableName(entity);
        if (table.isBlank()) {
            return false;
        }

        String column = columnName(entity, trimmedFieldPath);
        if (column.isBlank()) {
            return false;
        }

        String expectedText = String.valueOf(expectedValue).trim();
        if (expectedText.isEmpty()) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT 1 FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = :expectedValue"
            );
            query.setParameter("expectedValue", expectedText);
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
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
