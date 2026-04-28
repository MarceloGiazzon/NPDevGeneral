# NPDevKernel

## Purpose

Provide the semantic execution core for flows, events, capability binding, tracing, and invariant enforcement.

## Build

Use the Gradle kernel tasks to compile the core engine and adapter-facing contracts.

## Test

Run kernel unit and integration tests covering binding resolution, execution behavior, resume paths, and tenant-aware runtime rules.

## Architecture

NPDevKernel defines the execution semantics that adapters plug into; generated apps and RuntimeHost wire the kernel into a runnable application.
