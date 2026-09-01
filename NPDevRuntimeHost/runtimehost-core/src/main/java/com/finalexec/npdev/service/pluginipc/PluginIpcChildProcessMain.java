package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.ports.CapabilityAdapter;

import java.lang.reflect.Constructor;

/**
 * Real process entry point for a Model B plugin child (SEC-3, docs/architecture/
 * PLUGIN_PROCESS_ISOLATION_DESIGN.md section 6). {@link PluginIpcChildProcess} spawns this class as a
 * separate {@code java} process. Resolves a plugin handler class by reflection -- a public constructor
 * taking one {@link PluginIpcCallbackClient} -- the same shape the step-1 prototype's in-process handler
 * factories already used ({@code Function<PluginIpcCallbackClient, CapabilityAdapter>}), just resolved by
 * reflection instead of a Java lambda since a lambda cannot cross a process boundary. Talks the
 * {@link PluginIpcFrameCodec} wire format over its own {@code System.in}/{@code System.out}, exactly the
 * pipes {@code ProcessBuilder} gives every child for free.
 *
 * <p>Two modes, chosen by argument count:</p>
 * <ul>
 *   <li>{@code args[0]} present (SEC-3 step 2): one-shot -- the handler class is bound at spawn time,
 *       exactly one invocation is served, then the process exits. {@link PluginIpcChildProcess#start}.</li>
 *   <li>No args (SEC-3 step 3): pooled -- a fungible worker not bound to any one plugin class; each
 *       invoke frame names its own {@link com.finalexec.npdev.service.pluginipc.PluginIpcFrame.InvokeFrame#handlerClassName()},
 *       looping until the host closes the channel. {@link PluginIpcChildProcess#startPooled}.</li>
 * </ul>
 */
public final class PluginIpcChildProcessMain {

    private PluginIpcChildProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            new PluginIpcChildRuntime(System.in, System.out)
                    .runUntilClosed((invoke, callbackClient) ->
                            instantiateHandler(invoke.handlerClassName(), callbackClient));
            return;
        }
        if (args.length == 1 && !args[0].isBlank()) {
            String handlerClassName = args[0];
            new PluginIpcChildRuntime(System.in, System.out)
                    .runOnce(callbackClient -> instantiateHandler(handlerClassName, callbackClient));
            return;
        }
        System.err.println("usage: PluginIpcChildProcessMain [<handlerClassName>]");
        System.exit(2);
    }

    private static CapabilityAdapter instantiateHandler(String className, PluginIpcCallbackClient callbackClient) {
        if (className == null || className.isBlank()) {
            throw new IllegalStateException(
                    "Plugin IPC invoke frame carried no handlerClassName -- required in pooled mode"
            );
        }
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
