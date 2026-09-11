package com.npdev.generator.emitters;

/**
 * Version identity for the platform's thin browser shell (business-ui-index/app/style +
 * shell.js/shell.css, emitted byte-identical into every app by {@link BusinessUiEmitter} --
 * see docs/UI_CONTRACT.md's "Shell versioning" section). This is the one place that identity
 * is declared; BusinessUiEmitter threads it into the shell templates and into the
 * shell-manifest.json stamp file so a generated app carries a discoverable version rather than
 * an anonymous, unversioned copy.
 *
 * <p>The compat range names the runtime UI contract's own {@code schemaVersion} const (see
 * {@code schemas/ui-contract.schema.json}) that this shell build knows how to consume. Bump
 * {@link #SHELL_VERSION} whenever a shell template's runtime behavior changes in a way another
 * consumer (a future bundle-endpoint compat check, external tooling) needs to detect.
 */
public final class NpdevUiShellVersion {
    public static final String SHELL_VERSION = "npdev-ui-shell.v1";
    public static final String UI_CONTRACT_COMPAT_MIN = "npdev-ui-contract.v1";
    public static final String UI_CONTRACT_COMPAT_MAX = "npdev-ui-contract.v1";

    private NpdevUiShellVersion() {
    }
}
