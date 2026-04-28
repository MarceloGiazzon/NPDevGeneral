# NPDevContract

## Purpose

Define the shared schemas, examples, and contract fixtures that keep authoring, generation, and runtime aligned.

## Build

Use the Gradle tasks in the DSL module to compile and validate the contract assets.

## Test

Run the DSL test suite to verify schema conformance, parser behavior, compiled-model stability, and catalog expectations.

## Architecture

NPDevContract sits at the boundary between authoring and execution: the editor writes against it, the generator compiles it, and the runtime consumes the compiled surface.

NPDevContract is the shared agreement between the independent NPDev subprojects.

It contains JSON schemas, examples, and human-readable contract documents used by:

- NPDevEditor, which authors and validates model/config files.
- NPDevGenerator, which compiles those files into generated artifacts.
- NPDevRuntimeHost, which hosts generated apps.
- NPDevKernel, which executes flows, events, permissions, traces, and plugin calls at runtime.

Keep generated-app-specific domain vocabulary out of the contract schemas unless it is inside examples, samples, or tests.
