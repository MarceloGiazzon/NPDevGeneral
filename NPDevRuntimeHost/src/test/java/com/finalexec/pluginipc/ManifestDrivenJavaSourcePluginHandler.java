package com.finalexec.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifest;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifestLoader;
import com.finalexec.npdev.service.pluginipc.PluginIpcCallbackClient;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Test-only bridge, NOT shipped in any generated app: {@link com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessMain}
 * only knows how to reflectively instantiate a {@link CapabilityAdapter} with a public
 * {@code (PluginIpcCallbackClient)} constructor (the same shape every hand-written test handler in
 * this package uses), but a REAL generated {@code plugin:java-source} class is a raw POJO with no
 * such constructor -- production code adapts it via {@code ArtifactLocalJavaSourceCapabilityHandler},
 * constructed in-process with an already-resolved target instance + operation-&gt;method map, neither
 * of which can cross a process boundary as a single {@code handlerClassName} string.
 *
 * <p>This class closes that gap for the purpose of {@link PluginIpcChildProcessPoolRealPluginTest}
 * only: it IS the fixed {@code handlerClassName} the pool is given, and at {@link #invoke} time reads
 * the SAME {@code npdev/plugin-runtime/java-source-runtime-refs.json} manifest (on the child
 * process's classpath, since a pooled worker already runs on the host's own full classpath) to
 * resolve the real plugin's FQCN + method for the call's capability, then reflects into it --
 * mirroring {@code ArtifactLocalJavaSourceCapabilityHandler}'s own dispatch, not reusing it directly,
 * since that class's constructor shape does not fit here.</p>
 */
public final class ManifestDrivenJavaSourcePluginHandler implements CapabilityAdapter {

    public ManifestDrivenJavaSourcePluginHandler(PluginIpcCallbackClient callbackClient) {
        // Unused: every plugin.plugin:java-source fixture this test drives makes no host callback.
    }

    @Override
    public String adapterId() {
        return "plugin:java-source";
    }

    @Override
    public String capability() {
        return "*";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        JavaSourceRuntimeRefManifest manifest = new JavaSourceRuntimeRefManifestLoader(new ObjectMapper()).load();
        JavaSourceRuntimeRefManifest.Entry entry = manifest.entryForCapability(call.capability()).orElse(null);
        if (entry == null) {
            return CapabilityResult.failure(
                    "JAVA_SOURCE_RUNTIME_REF_NOT_FOUND",
                    "No java-source-runtime-refs.json entry for capability " + call.capability(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability())
            );
        }
        String methodName = entry.methodByOperation().get(call.operation());
        if (methodName == null) {
            return CapabilityResult.failure(
                    "JAVA_SOURCE_OPERATION_NOT_BOUND",
                    "Java source capability operation is not bound: " + call.operation(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability(), "operation", call.operation())
            );
        }
        try {
            Class<?> pluginClass = Class.forName(entry.mainClass());
            Object target = pluginClass.getDeclaredConstructor().newInstance();
            Method method = pluginClass.getMethod(methodName, Map.class);
            Object output = method.invoke(target, call.input());
            return CapabilityResult.success(output);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return CapabilityResult.failure(
                    "JAVA_SOURCE_CAPABILITY_FAILED",
                    cause.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("exceptionType", cause.getClass().getName())
            );
        } catch (ReflectiveOperationException exception) {
            return CapabilityResult.failure(
                    "JAVA_SOURCE_CAPABILITY_DISPATCH_ERROR",
                    exception.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("exceptionType", exception.getClass().getName())
            );
        }
    }
}
