# Canonical Demo Model

This sample root now holds the frozen canonical demo specimen for NPDev.

## Why this exists

The canonical demo is the platform-owned reference model used for:

- regression-oriented smoke generation
- documentation and screenshots
- editor and preview expectations
- explainability examples
- onboarding discussions about the platform specimen

## What it contains

The canonical demo is a compact healthcare scenario with four concepts:

- `Patient`
- `Provider`
- `Appointment`
- `InsuranceClaim`

It demonstrates:

- reference fields across concepts
- lifecycle state transitions
- explicit state-machine states and named transition actions on `Appointment`
- an event family centered on `AppointmentCompleted`
- a downstream orchestration rule that creates an insurance claim and invokes notification
- nested data and UI metadata in a learnable specimen
- deterministic defaults and derived-field behavior on `Patient`

## How to use it

- `Input/model.json`
- `Input/config.json`
- `Input/expected-behavior.md`
- generated output under `Output/ArtifactNP` and `Output/App`

## Standard layout

- `Input`: source assets and docs
- `Output`: generated artifact and runnable app

