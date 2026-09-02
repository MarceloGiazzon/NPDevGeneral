package com.finalexec.db.schemastate;

/**
 * How one {@link SchemaDiffItem} in a schema-ahead diagnosis (B5-A, boundary-lift 2026-09-02 package
 * 2.3) should be read by an operator staring at a boot refusal. Derived straight from the item's own
 * {@link SafetyClass} -- see {@code SchemaAheadAnalysis.resolutionFor} for the exact mapping. Advisory
 * only: nothing here changes whether the boot refuses (it always does, per B5) or runs any DDL --
 * {@code --schema-ahead-mode=IGNORE} was explicitly rejected when this package was scoped.
 */
public enum SchemaAheadResolution {
    /** This build can boot against the live schema as-is -- the difference is something the newer
     *  build's schema is stricter or narrower about than this build needs, or something extra the live
     *  schema carries that this build's own code never references. */
    PROCEED_IGNORING,
    /** This build's own schema expects a table, column, or width the live (newer) schema no longer
     *  provides -- the fix is to run the newer build, not to force this one to boot. */
    NEEDS_NEWER_BUILD,
    /** Reconciling the live schema down to this build's own shape would destroy data the newer build
     *  added (dropping a table/column, or narrowing a type) -- restoring a pre-upgrade database
     *  snapshot is the only way back, not an automatic downgrade. */
    NEEDS_DESTRUCTIVE_DOWNGRADE
}
