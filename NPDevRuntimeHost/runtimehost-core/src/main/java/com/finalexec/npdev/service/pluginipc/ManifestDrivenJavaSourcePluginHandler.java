package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SEC-5: shipped in every generated app (runtimehost-core), the fixed {@code handlerClassName} every
 * {@link PluginIpcChildProcessPool} worker uses for a {@code plugin:java-source} invoke.
 * {@link PluginIpcChildProcessMain} only knows how to reflectively instantiate a {@link
 * CapabilityAdapter} with a public {@code (PluginIpcCallbackClient)} constructor -- the same shape
 * every hand-written test handler in this package uses -- but a real generated {@code
 * plugin:java-source} class is a raw POJO with no such constructor, so this class bridges the two: at
 * {@link #invoke} time it reads {@code npdev/plugin-runtime/java-source-runtime-refs.json} (on the
 * child process's own classpath, since a pooled worker runs on the host's own full classpath) to
 * resolve the real plugin's FQCN + method for the call's capability, then reflects into it -- mirroring
 * {@code ArtifactLocalJavaSourceCapabilityHandler}'s own dispatch, not reusing it directly, since that
 * class's constructor shape (an already-resolved in-process target instance) cannot cross a process
 * boundary as a single {@code handlerClassName} string.
 *
 * <p>Originally a test-only bridge (SEC-3, proven by {@code PluginIpcChildProcessPoolRealPluginTest}
 * against a real plugin); promoted to main sources here so {@link PluginIpcCapabilityHandler} has a
 * real, shipped class name to hand the pool for production traffic.</p>
 */
public final class ManifestDrivenJavaSourcePluginHandler implements CapabilityAdapter {

    public ManifestDrivenJavaSourcePluginHandler(PluginIpcCallbackClient callbackClient) {
        // Unused: every plugin:java-source fixture proven against this handler makes no host callback.
        // A real java-source plugin that DOES call back into the host still reaches PluginIpcHostSession
        // via the same wire protocol PluginIpcChildProcessMain already wires this constructor into.
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
        // REG-218: methodByOperation's keys are always lowercased (RuntimeApiEmitter writes them
        // that way generator-side), but call.operation() carries the model's real camelCase
        // operation name -- a case-exact Map.get() here can never match. Normalize the same way
        // ArtifactLocalJavaSourceCapabilityHandler.normalize() already does for the in-process path.
        String methodName = entry.methodByOperation().get(normalizeOperation(call.operation()));
        if (methodName == null) {
            return CapabilityResult.failure(
                    "JAVA_SOURCE_OPERATION_NOT_BOUND",
                    "Java source capability operation is not bound: " + call.operation(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability(), "operation", call.operation())
            );
        }
        try {
            // SEC-10 (B30 lift): the PLUGIN class itself loads through a restricted classloader --
            // this handler's own reflective dispatch below is host code and stays on the normal
            // classloader. See PluginRestrictedClassLoader's own javadoc for what this closes.
            Class<?> pluginClass = Class.forName(
                    entry.mainClass(), true, new PluginRestrictedClassLoader(getClass().getClassLoader()));
            Object target = pluginClass.getDeclaredConstructor().newInstance();
            // REG-223: a hardcoded getMethod(methodName, Map.class) + call.input() (the first arg
            // only) silently resolved the WRONG overload -- and silently dropped every arg past the
            // first -- for any multi-arg capabilityCall (e.g. fiscalImport.importarRomaneio's 3-arg
            // overload, args={input, numerosJaImportados, produtosConhecidos}). Resolve by
            // (name, argCount) and invoke with the FULL argument list, matching
            // ArtifactLocalJavaSourceCapabilityHandler.resolveMethod's already-correct behavior for
            // the in-process path.
            List<Object> args = call.args();
            Method method = resolveMethod(pluginClass, methodName, args == null ? 0 : args.size());
            Object output = method.getParameterCount() == 0
                    ? method.invoke(target)
                    : method.invoke(target, args.toArray());
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

    private static String normalizeOperation(String operation) {
        return operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
    }

    private static Method resolveMethod(Class<?> pluginClass, String methodName, int argCount) throws NoSuchMethodException {
        for (Method method : pluginClass.getMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == argCount
                    && !method.getDeclaringClass().equals(Object.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException(pluginClass.getName() + "." + methodName + " with " + argCount + " argument(s)");
    }
}
