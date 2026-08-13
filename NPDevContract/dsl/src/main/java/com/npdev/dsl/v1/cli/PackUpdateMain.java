package com.npdev.dsl.v1.cli;

/**
 * {@code npdev pack update}: re-runs discovery+MVS and rewrites {@code npdev.lock} -- identical
 * mechanism to {@code npdev pack add} (see {@link PackAddMain}), kept as a separate command only
 * for CLI-surface clarity (a user reaching for "update" after editing a constraint shouldn't have
 * to know "add" does the same thing).
 */
public final class PackUpdateMain {

    private PackUpdateMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        return PackAddMain.run(args);
    }
}
