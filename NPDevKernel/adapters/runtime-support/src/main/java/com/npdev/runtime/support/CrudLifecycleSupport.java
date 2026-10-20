package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.UserScopedExpressions;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport.InvariantViolationDetail;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.asBoolean;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.resolveStateMachineGuardValue;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.valuesEqual;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.extractCurrentId;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.isLifecycleMissing;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readLifecycleToken;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.columnName;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.fetchCurrentStatusSql;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.tableName;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;

/**
 * CARVE-3: lifecycle (state-machine) transition enforcement, moved byte-faithfully out of
 * GeneratedCrudRuntimeSupport. Holds only the EntityManager (status read-back) and a reference to its
 * owner for the two model/context lookups the moved bodies call.
 */
final class CrudLifecycleSupport {
    private final GeneratedCrudRuntimeSupport owner;
    private final EntityManager entityManager;

    CrudLifecycleSupport(GeneratedCrudRuntimeSupport owner, EntityManager entityManager) {
        this.owner = owner;
        this.entityManager = entityManager;
    }

    private Optional<CompiledField> idField(CompiledConcept entity) {
        return owner.idField(entity);
    }

    private ExecutionContext resolveCurrentExecutionContext() {
        return owner.resolveCurrentExecutionContext();
    }

    void validateLifecycleTransitions(
            String entityName,
            CompiledConcept entity,
            Map<String, Object> payload,
            List<InvariantViolationDetail> violations
    ) {
        if (entity == null || payload == null || payload.isEmpty() || violations == null) {
            return;
        }
        CompiledLifecycle lifecycle = entity.getLifecycle();
        if (lifecycle == null || lifecycle.getTransitions().isEmpty()) {
            return;
        }

        UUID currentId = extractCurrentId(payload);
        if (currentId == null) {
            return;
        }

        String statusField = lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()
                ? "status"
                : lifecycle.getStatusField().trim();
        String nextStatus = readLifecycleToken(readPayloadValue(payload, statusField));
        if (nextStatus == null) {
            return;
        }

        String previousStatus = fetchCurrentStatus(entity, currentId, statusField);
        if (previousStatus == null || normalize(previousStatus).equals(normalize(nextStatus))) {
            return;
        }

        CompiledStateTransition matchingTransition = findLifecycleTransition(lifecycle, previousStatus, nextStatus);
        if (matchingTransition == null) {
            violations.add(new InvariantViolationDetail(
                    "invariant_failed",
                    entityName,
                    "status_transition_not_allowed",
                    statusField,
                    "Status transition from '" + previousStatus + "' to '" + nextStatus + "' is not allowed",
                    false
            ));
            return;
        }

        for (String requiredField : matchingTransition.getRequiredPayload()) {
            if (requiredField == null || requiredField.isBlank()) {
                continue;
            }
            Object value = readPayloadValue(payload, requiredField);
            if (isLifecycleMissing(value)) {
                violations.add(new InvariantViolationDetail(
                        "invariant_failed",
                        entityName,
                        "status_transition_requires_field",
                        requiredField,
                        "Status transition to '" + matchingTransition.getTo()
                                + "' requires field '" + requiredField + "'",
                        false
                ));
            }
        }

        if (matchingTransition.getGuard() != null
                && !matchingTransition.getGuard().isBlank()
                && !evaluateStateMachineGuard(matchingTransition.getGuard(), payload, previousStatus, nextStatus)) {
            violations.add(new InvariantViolationDetail(
                    "invariant_failed",
                    entityName,
                    "status_transition_guard_failed",
                    statusField,
                    "Status transition from '" + previousStatus + "' to '" + nextStatus
                            + "' failed guard '" + matchingTransition.getGuard() + "'",
                    false
            ));
        }
    }

    private boolean evaluateStateMachineGuard(
            String rawCondition,
            Map<String, Object> payload,
            String previousStatus,
            String nextStatus
    ) {
        if (rawCondition == null || rawCondition.isBlank()) {
            return true;
        }
        String condition = rawCondition.trim();
        if ("true".equalsIgnoreCase(condition)) {
            return true;
        }
        if ("false".equalsIgnoreCase(condition)) {
            return false;
        }
        // A guard naming the acting user ($user.roles.contains('Curator')) goes through the same
        // evaluator the concept gateway and row access use, never the legacy matcher below.
        if (UserScopedExpressions.referencesUser(condition)) {
            return UserScopedExpressions.evaluate(condition, payload, resolveCurrentExecutionContext());
        }

        int equalsIndex = condition.indexOf("==");
        if (equalsIndex >= 0) {
            Object left = resolveStateMachineGuardValue(condition.substring(0, equalsIndex), payload, previousStatus, nextStatus);
            Object right = resolveStateMachineGuardValue(condition.substring(equalsIndex + 2), payload, previousStatus, nextStatus);
            return valuesEqual(left, right);
        }

        int notEqualsIndex = condition.indexOf("!=");
        if (notEqualsIndex >= 0) {
            Object left = resolveStateMachineGuardValue(condition.substring(0, notEqualsIndex), payload, previousStatus, nextStatus);
            Object right = resolveStateMachineGuardValue(condition.substring(notEqualsIndex + 2), payload, previousStatus, nextStatus);
            return !valuesEqual(left, right);
        }

        return asBoolean(resolveStateMachineGuardValue(condition, payload, previousStatus, nextStatus));
    }

    CompiledStateTransition findLifecycleTransition(
            CompiledLifecycle lifecycle,
            String previousStatus,
            String nextStatus
    ) {
        if (lifecycle == null || lifecycle.getTransitions() == null) {
            return null;
        }
        String previous = normalize(previousStatus);
        String next = normalize(nextStatus);
        for (CompiledStateTransition transition : lifecycle.getTransitions()) {
            if (transition == null) {
                continue;
            }
            if (normalize(transition.getFrom()).equals(previous)
                    && normalize(transition.getTo()).equals(next)) {
                return transition;
            }
        }
        return null;
    }

    private String fetchCurrentStatus(CompiledConcept entity, UUID id, String statusField) {
        if (entityManager == null || entity == null || id == null) {
            return null;
        }
        String table = tableName(entity);
        if (table == null || table.isBlank()) {
            return null;
        }
        String statusColumn = columnName(entity, statusField);
        Optional<CompiledField> idField = idField(entity);
        if (statusColumn.isBlank() || idField.isEmpty()) {
            return null;
        }
        try {
            Query query = entityManager.createNativeQuery(fetchCurrentStatusSql(entity, idField.get(), statusColumn));
            query.setParameter("id", id.toString());
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return readLifecycleToken(rows.get(0));
        } catch (Exception ignored) {
            return null;
        }
    }
}
