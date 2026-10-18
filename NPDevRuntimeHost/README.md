# NPDevRuntimeHost

## Purpose

Provide the template runtime shell that hosts assembled NPDev applications, migration assets, runtime services, and evidence-oriented support surfaces.

## Build

Do not build this directory directly: it is a template (`build.gradle` says so). Generation copies it into each FinalApp, and you build from the generated final app root.

## Test

Run the runtimehost gate, beta release gate, migration checks, and focused integration tests for tenant, publication, resume, and support surfaces.

## Architecture

NPDevRuntimeHost is the runtime template layer between generator output and the final runnable app, carrying shared services, migrations, and operational diagnostics.
