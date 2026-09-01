package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.ports.CapabilityAdapter;

import java.lang.reflect.Constructor;

/**
 * Real process entry point for a Model B plugin child (SEC-3, step 2 --
 * docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 6). {@link PluginIpcChildProcess} spawns
 * this class as a separate {@code java} process with {@code args[0]} naming a {@link CapabilityAdapter}
 * implementation that has a public constructor taking one {@link PluginIpcCallbackClient} -- the same
 * shape the step-1 prototype's in-process handler factories already used
 * ({@code Function<PluginIpcCallbackClient, CapabilityAdapter>}), just resolved by reflection instead of
 * a Java lambda since a lambda cannot cross a process boundary. Talks the {@link PluginIpcFrameCodec}
 * wire format over its own {@code System.in}/{@code System.out}, exactly the pipes {@code ProcessBuilder}
 * gives every child for free. Handles exactly one invocation, then exits -- no pool yet (step 3).
 */
public final class PluginIpcChildProcessMain {

    private PluginIpcChildProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("usage: PluginIpcChildProcessMain <handlerClassName>");
            System.exit(2);
            return;
        }
        String handlerClassName = args[0];
        new PluginIpcChildRuntime(System.in, System.out)
                .runOnce(callbackClient -> instantiateHandler(handlerClassName, callbackClient));
    }

    private static CapabilityAdapter instantiateHandler(String className, PluginIpcCallbackClient callbackClient) {
        try {
            Class<?> handlerClass = Class.forName(className);
            Constructor<?> constructor = handlerClass.getConstructor(PluginIpcCallbackClient.class);
            return (CapabilityAdapter) constructor.newInstance(callbackClient);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Unable to instantiate plugin IPC child handler '" + className + "': " + exception, exception
            );
        }
    }
}
