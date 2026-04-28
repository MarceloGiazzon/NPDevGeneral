# NPDevEditor

## Purpose

Provide the authoring and workbench surface for building, validating, previewing, and exporting NPDev models and configs.

## Build

Use the React/Node build in `ui-react` for the workbench surface and the local scripts that assemble editor assets.

## Test

Run the frontend gate and the focused editor round-trip, validation, and UX tests.

## Architecture

NPDevEditor owns the authoring experience only; it emits model/config artifacts and does not replace the generator or runtime host responsibilities.
