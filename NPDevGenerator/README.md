# NPDevGenerator

## Purpose

Compile NPDev contract inputs into generated artifact trees, runtime app sources, migration plans, and deterministic compiled-model assets.

## Build

Use the Gradle generator tasks to compile templates, assembler logic, and migration emitters.

## Test

Run the generator gate (`scripts/quality/run-generator-gate.ps1`, which also runs the determinism check `scripts/hygiene/check-deterministic-generation.ps1`) plus the focused regeneration-evolution, migration, and projection-guard tests.

## Architecture

NPDevGenerator sits between contract and runtime: it consumes canonical model/config inputs and emits the assembled runtime/application surface.
