package com.npdev.generator.dbconfig;

import java.util.Locale;

/**
 * How an app's schema is reconciled with its model at boot.
 *
 * <h2>STOR-16: why {@link #EPHEMERAL} exists and {@link #RECREATE_ON_APP_START} does not</h2>
 * Measured 2026-08-17: {@code SchemaLifecycleExecutor} read {@code strategy} at exactly one line,
 * comparing it to {@code "DropAndRecreateOnStructureChange"}. {@code RecreateOnAppStart} had NO code
 * path at all -- despite the name, it recreated nothing. Meanwhile
 * {@code DropAndRecreateOnStructureChange} + {@code allowDestructiveRecreate} authorizes surgical
 * column drops and type narrowings ONLY; dropping a concept, or any diff that cannot be itemized,
 * still refuses and demands an acknowledgment token (hardenings X4.4 / C1). So a developer
 * iterating on a throwaway app got the production-grade refusal and had no posture that said
 * "this data is disposable".
 *
 * <p>{@code Ephemeral} is a fourth STRATEGY rather than a fifth boolean deliberately. A distinct
 * strategy value can bypass the itemized-token path BY CONSTRUCTION -- a boolean combination has to
 * be checked at every refusal site and will eventually be missed at one of them -- and it is
 * greppable: {@code grep Ephemeral} finds every site that reasons about it.
 *
 * <p>{@code RecreateOnAppStart} is DEPRECATED to an alias rather than deleted, and that is safe by
 * measurement rather than by assumption: all 7 corpus definitions using it are {@code InMemory},
 * where "drop and recreate on boot" and "memory is empty on boot" are the same thing. The alias is
 * accepted, resolves to {@code EPHEMERAL}, and {@link #isDeprecatedSpelling(String)} lets callers
 * warn and name the new spelling.
 */
public enum SchemaLifecycleStrategy {

    /**
     * Reconcile structure changes destructively, but ONLY where the change can be itemized -- and
     * refuse, demanding an acknowledgment token, where it cannot. This is the production posture
     * for a database whose contents matter.
     */
    DROP_AND_RECREATE_ON_STRUCTURE_CHANGE("DropAndRecreateOnStructureChange"),

    /**
     * The app's data is disposable: on every start, NPDev drops the tables the manifest declares it
     * owns and recreates them from the model. No diff, no impact report, no acknowledgment token.
     *
     * <p>Scoped to NPDev-owned tables by construction, never "everything in the schema": an app
     * pointed at a database it shares must not take the neighbour's tables with it.
     */
    EPHEMERAL("Ephemeral"),

    /** Keep whatever is there when it is compatible with the model. The default, and the only
     *  posture valid for a database NPDev does not own. */
    KEEP_EXISTING_IF_COMPATIBLE("KeepExistingIfCompatible");

    /** The retired spelling {@link #EPHEMERAL} replaced. Accepted, warned about, never emitted. */
    public static final String DEPRECATED_RECREATE_ON_APP_START = "RecreateOnAppStart";

    private final String externalName;

    SchemaLifecycleStrategy(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    /** True when {@code value} is the retired {@code RecreateOnAppStart} spelling. Callers use this
     *  to emit a deprecation warning naming {@code Ephemeral}; parsing itself still succeeds. */
    public static boolean isDeprecatedSpelling(String value) {
        return value != null
                && DEPRECATED_RECREATE_ON_APP_START.toLowerCase(Locale.ROOT)
                        .equals(value.trim().toLowerCase(Locale.ROOT));
    }

    /** The warning text for a definition still using the retired spelling. One place, so the CLI,
     *  the generator and any future caller say the same thing. */
    public static String deprecationWarning() {
        return "schemaLifecycle.strategy=" + DEPRECATED_RECREATE_ON_APP_START + " is deprecated and "
                + "now means '" + EPHEMERAL.externalName() + "' (STOR-16). The old name never had a "
                + "code path of its own -- it recreated nothing. Run `npdev migrate db-lifecycle "
                + "--input <dir> --write` to update your db.definition.json.";
    }

    public static SchemaLifecycleStrategy parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("schemaLifecycle.strategy is required");
        }
        if (isDeprecatedSpelling(value)) {
            return EPHEMERAL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SchemaLifecycleStrategy strategy : values()) {
            if (strategy.externalName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unsupported schemaLifecycle.strategy: " + value);
    }
}
