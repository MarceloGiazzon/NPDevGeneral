package com.npdev.dsl.v1.cli;

/** Test-only bridge to the package-private {@code PackSignMain.run}, which returns an exit code instead of exiting. */
public final class PackSignMainTestAccess {

    private PackSignMainTestAccess() {
    }

    public static int run(String[] args) {
        return PackSignMain.run(args);
    }
}
