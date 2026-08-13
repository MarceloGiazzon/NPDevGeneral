package com.finalexec.db.schemastate;

/**
 * How a {@link SchemaDiffItem} has been resolved (schema-engine rebuild, Phase 2). Starts
 * {@link #UNRESOLVED}; a later phase advances it: {@link #AUTO} when a safe pass applied it,
 * {@link #HOOK_CLAIMED} when a Phase-7 conversion hook took responsibility for it, or
 * {@link #ACKNOWLEDGED} when an operator's destructive-acknowledgment token cleared it.
 */
public enum Resolution {
    UNRESOLVED,
    AUTO,
    HOOK_CLAIMED,
    ACKNOWLEDGED
}
