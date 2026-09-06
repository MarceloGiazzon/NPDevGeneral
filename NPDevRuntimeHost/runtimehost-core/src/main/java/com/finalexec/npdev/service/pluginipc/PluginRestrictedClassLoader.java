package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.security.TrustedSourceBytecodeInspector;

import java.util.Set;

/**
 * SEC-10 (B30 lift): loads a {@code plugin:java-source}/{@code plugin:java-controller} class itself
 * (never the host-side handler classes that reflect into it), denying two things a restricted
 * child-process classpath cannot: (1) the SAME escape-class denylist {@link
 * TrustedSourceBytecodeInspector} already refuses at admission time, closing the gap where a class
 * name is constructed at runtime rather than appearing as a static bytecode reference; (2) any
 * {@code com.npdev.generated.*} or {@code com.finalexec.*} class OUTSIDE the one package a mounted
 * plugin is admitted under ({@code com.npdev.generated.plugin.} -- the SAME prefix
 * {@code GeneratedPluginMountPlan.PLUGIN_CONTROLLER_PACKAGE_PREFIX} already enforces at generation
 * time) -- this is the "proxy class elsewhere on the app classpath" escape named in B30's own
 * residue, closed here because the app's own compiled-classes directory cannot be physically split
 * from the plugin classes living inside it (they are one Gradle compilation unit), so the real
 * boundary has to be enforced by the loader, not by {@link PluginChildClasspath}'s physical
 * reduction alone.
 *
 * <p>Deliberately mirrors {@link TrustedSourceBytecodeInspector}'s own prefix-exemption semantics
 * (not a fresh, stricter denylist) -- a plugin already admitted past the bytecode gate must not
 * fail to RUN here for a reference the admission gate itself would have allowed (e.g.
 * {@code java/io/PrintStream}, reached only via {@code System.out}/{@code System.err}).</p>
 */
final class PluginRestrictedClassLoader extends ClassLoader {

    static final String ALLOWED_GENERATED_PREFIX = "com/npdev/generated/plugin/";
    private static final String GENERATED_PREFIX = "com/npdev/generated/";
    private static final String RUNTIME_HOST_PREFIX = "com/finalexec/";

    static {
        ClassLoader.registerAsParallelCapable();
    }

    PluginRestrictedClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> alreadyLoaded = findLoadedClass(name);
            if (alreadyLoaded != null) {
                if (resolve) {
                    resolveClass(alreadyLoaded);
                }
                return alreadyLoaded;
            }
            if (isDenied(name.replace('.', '/'))) {
                throw new ClassNotFoundException("B30: plugin class access denied: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }

    /** Package-visible for {@code PluginRestrictedClassLoaderTest} -- pure logic, no classloading needed. */
    static boolean isDenied(String internalName) {
        if (internalName.startsWith(GENERATED_PREFIX) && !internalName.startsWith(ALLOWED_GENERATED_PREFIX)) {
            return true;
        }
        if (internalName.startsWith(RUNTIME_HOST_PREFIX)) {
            return true;
        }
        for (String prefix : TrustedSourceBytecodeInspector.FORBIDDEN_OWNER_PREFIXES) {
            if (internalName.startsWith(prefix)) {
                Set<String> exemptions = TrustedSourceBytecodeInspector.FORBIDDEN_OWNER_PREFIX_EXEMPTIONS.get(prefix);
                return exemptions == null || !exemptions.contains(internalName);
            }
        }
        return TrustedSourceBytecodeInspector.FORBIDDEN_OWNERS.contains(internalName);
    }
}
