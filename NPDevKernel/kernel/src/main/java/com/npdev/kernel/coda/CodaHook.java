package com.npdev.kernel.coda;

import com.npdev.kernel.ExecutionContext;

import java.util.Map;

/**
 * The single defined extension point for author-supplied code (the "Coda" gap): a concept gated
 * by {@code coda.allowed} calls into an implementation of this interface, if one is present on the
 * generated app's classpath, immediately before and after each CRUD mutation. All methods are
 * default no-ops so a partial implementation (only some of them) is valid.
 *
 * <p>Deliberately narrow scope -- this is NOT a general plugin/extension system: one interface,
 * fixed points in the create/update/delete paths (mirroring exactly where the Flow-CRUD wrapper
 * hooks in), no dynamic loading/sandboxing. An app supplies at most one {@code CodaHook} bean; if
 * none exists, the generated service's behavior is completely unchanged (every hook call is a
 * no-op).</p>
 */
public interface CodaHook {

    /** Called with the already-validated create payload, before the concept gateway/Flow mutation runs. */
    default void beforeCreate(String conceptName, Map<String, Object> payload, ExecutionContext context) {
    }

    /** Called with the persisted entity (as a snapshot map), after create succeeds. */
    default void afterCreate(String conceptName, Object createdEntity, ExecutionContext context) {
    }

    /** Called with the already-validated update payload, before the concept gateway/Flow mutation runs. */
    default void beforeUpdate(String conceptName, java.util.UUID id, Map<String, Object> payload, ExecutionContext context) {
    }

    /** Called with the persisted entity (as a snapshot map), after update succeeds. */
    default void afterUpdate(String conceptName, Object updatedEntity, ExecutionContext context) {
    }

    /** Called with the target id, before the concept gateway/Flow delete mutation runs. */
    default void beforeDelete(String conceptName, java.util.UUID id, ExecutionContext context) {
    }

    /** Called with the target id, after delete succeeds. */
    default void afterDelete(String conceptName, java.util.UUID id, ExecutionContext context) {
    }
}
