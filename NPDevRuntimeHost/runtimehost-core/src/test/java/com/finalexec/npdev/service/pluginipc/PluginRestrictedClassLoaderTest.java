package com.finalexec.npdev.service.pluginipc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-10 (B30 lift): pure-logic coverage of {@link PluginRestrictedClassLoader#isDenied}, the
 * decision a mounted plugin's own class-load requests are checked against. No real classloading
 * needed -- this is the same reason {@link PluginRestrictedClassLoader#isDenied} is a static method
 * in the first place.
 */
class PluginRestrictedClassLoaderTest {

    @Test
    void aPluginClassUnderTheReservedPluginPackageIsAllowed() {
        assertFalse(PluginRestrictedClassLoader.isDenied("com/npdev/generated/plugin/InvoiceTotals"));
    }

    @Test
    void anyOtherGeneratedAppClassOutsideThePluginPackageIsDenied() {
        assertTrue(PluginRestrictedClassLoader.isDenied("com/npdev/generated/runtime/service/KernelFacade"));
        assertTrue(PluginRestrictedClassLoader.isDenied("com/npdev/generated/controllers/GeneratedQueryController"));
    }

    @Test
    void anyRuntimeHostTemplateClassIsDenied() {
        assertTrue(PluginRestrictedClassLoader.isDenied("com/finalexec/config/ModelHolder"));
        assertTrue(PluginRestrictedClassLoader.isDenied("com/finalexec/db/SchemaLifecycleExecutor"));
    }

    @Test
    void kernelAndJacksonClassesAreAllowed() {
        assertFalse(PluginRestrictedClassLoader.isDenied("com/npdev/kernel/CapabilityCall"));
        assertFalse(PluginRestrictedClassLoader.isDenied("com/fasterxml/jackson/databind/ObjectMapper"));
    }

    @Test
    void filesystemNetworkAndProcessControlEscapesAreDenied() {
        assertTrue(PluginRestrictedClassLoader.isDenied("java/io/File"));
        assertTrue(PluginRestrictedClassLoader.isDenied("java/nio/file/Files"));
        assertTrue(PluginRestrictedClassLoader.isDenied("java/net/Socket"));
        assertTrue(PluginRestrictedClassLoader.isDenied("java/lang/reflect/Method"));
        assertTrue(PluginRestrictedClassLoader.isDenied("java/lang/ProcessBuilder"));
        assertTrue(PluginRestrictedClassLoader.isDenied("java/lang/Runtime"));
        assertTrue(PluginRestrictedClassLoader.isDenied("java/lang/Thread"));
    }

    @Test
    void theSameOwnerPrefixExemptionTheBytecodeAdmissionGateGrantsIsHonoredHere() {
        // A plugin already admitted past TrustedSourceBytecodeInspector must not fail to RUN here
        // for the one reference that inspector itself exempts (System.out/err's PrintStream).
        assertFalse(PluginRestrictedClassLoader.isDenied("java/io/PrintStream"));
    }

    @Test
    void ordinaryJavaBaseClassesAreAllowed() {
        assertFalse(PluginRestrictedClassLoader.isDenied("java/lang/String"));
        assertFalse(PluginRestrictedClassLoader.isDenied("java/util/Map"));
        assertFalse(PluginRestrictedClassLoader.isDenied("java/util/ArrayList"));
    }

    @Test
    void springWebAnnotationClassesNeededByAPluginControllerAreAllowed() {
        assertFalse(PluginRestrictedClassLoader.isDenied("org/springframework/web/bind/annotation/PathVariable"));
    }

    /**
     * A {@code plugin:java-source} mount (and a {@code conversions[].javaHook} migration hook,
     * which reuses this same bridge -- see {@code JavaMigrationHookRunner}) has NO enforced package
     * prefix at generation time, unlike {@code plugin:java-controller}'s
     * {@code com.npdev.generated.plugin.} requirement ({@code GeneratedPluginMountPlan} lines
     * 180-295 vs. 350) -- the author's own class can live anywhere. This is the real, committed
     * corpus fixture's actual package (dsl-conformance-max's {@code OrderSummaryHook}), pinned here
     * so a future tightening of this denylist cannot silently break a real java migration hook the
     * way the first draft of this classloader would have (traced, not assumed, after that exact
     * class of regression surfaced against a differently-misplaced test fixture during this
     * package's own implementation).
     */
    @Test
    void anAuthorChosenArbitraryPackageForAJavaSourceOrHookClassIsAllowed() {
        assertFalse(PluginRestrictedClassLoader.isDenied("com/npdev/samples/dslconformance/conversion/OrderSummaryHook"));
    }
}
