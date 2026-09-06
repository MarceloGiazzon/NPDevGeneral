package com.finalexec.npdev.service.pluginipc;

import java.io.File;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SEC-10 (B30 lift), step 5: names what is ACTUALLY active, once, at boot -- SEC-7's own precedent
 * ("an unenforceable limit announces itself loudly rather than passing silently") applied to the
 * plugin containment stack as a whole rather than to one mechanism at a time.
 */
public final class PluginContainmentTierLog {

    private static final Logger LOG = Logger.getLogger(PluginContainmentTierLog.class.getName());

    private PluginContainmentTierLog() {
    }

    public static void logAtBoot(String restrictedClasspath, PluginProcessResourceLimits limits) {
        int entryCount = restrictedClasspath.isBlank()
                ? 0
                : restrictedClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator)).length;
        PluginProcessResourceLimiter limiter = PluginProcessResourceLimiter.forCurrentOs();
        String osTier;
        if (limiter.networkFilesystemSandboxActive(limits)) {
            osTier = "os-sandbox(linux)";
        } else if (isWindows()) {
            osTier = "os-sandbox(unavailable on this platform -- Windows has no network/filesystem "
                    + "Job Object equivalent; UI-restriction hardening only)";
        } else {
            osTier = "os-sandbox(unavailable on this platform)";
        }
        LOG.log(Level.INFO, "plugin containment: classpath-restricted (" + entryCount + " entries) "
                + "+ classloader-denylist + " + osTier);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
