# NPDev Internal DB Schema Source of Truth

## Status

Mandatory architecture policy.

## Purpose

NPDev internal/system database tables must have one source of truth.

The source of truth is Java table-definition code under:

```text
D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\kernel\src\main\java\com\npdev\kernel\dbschema
```

The central registry is:

```text
D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\kernel\src\main\java\com\npdev\kernel\dbschema\NpdevInternalTables.java
```

## Rule

NPDev internal/system table schema must be defined by Java dbschema classes.

SQL is an emitted artifact.

Generated SQL must not become the authority.

Hand-authored internal migration SQL must not be reintroduced as an authority.

## Why this rule exists

NPDev supports multiple storage modes and database engines, including:

```text
InMemory
H2Local
H2Server
Postgres
```

A hand-authored SQL source of truth causes drift:

```text
Postgres SQL says one thing.
H2 SQL says another thing.
Java adapters assume another thing.
Generated runtime manifests list another thing.
```

The correct architecture is:

```text
Java dbschema definitions
  -> dialect-aware schema realization
  -> generated SQL / logical stores
  -> generated manifest
  -> runtime validation
```

## Current actual internal/system tables

The current actual Java dbschema registry must cover only the current implemented internal/system tables:

```text
npdev_audit_log
npdev_circuit_breaker
npdev_correlation_owner
npdev_event_store
npdev_flow_instance
npdev_idempotency
npdev_publication_audit
npdev_publication_execution
npdev_schema_metadata
npdev_scheduled_event
npdev_trace
```

Do not add future tables under this policy item.

## Future tables are out of scope for this model-strengthening step

The following future design areas must not be implemented as part of this model-strengthening step:

```text
TenantProvider tables
Coda tables
Capability tables
Orchestration tables
Flow definition / flow step tables
```

Those are separate work items.

## Logical column type policy

Internal dbschema columns use logical Java column types such as:

```text
TEXT
LARGE_TEXT
JSON_DOCUMENT
TIMESTAMP
INTEGER
BIGINT
```

These types describe NPDev's internal schema model.

They are not the final database dialect SQL.

Dialect-aware schema realization maps logical types to generated SQL.

## Generated SQL policy

Generated app SQL may exist at locations such as:

```text
D:\WorkSpace\NPDev\Build\generated-finalapps\<app-id>\App\src\main\resources\db\schema-realization\V1__npdev_schema_realization.sql
```

This SQL is output.

It must be reproducible from the Java dbschema registry plus business model definitions.

It must not be edited by hand.

## Legacy migration policy

Old `V5001..V5014`-style internal migration SQL must not be treated as the source of truth.

If legacy files exist for reference, they must be quarantined outside active product paths and marked as legacy.

No new internal/system table should be added as a hand-authored migration file.

The old generator migration/model-diff package under `NPDevGenerator/generator/src/main/java/com/npdev/generator/migration` was removed from active source after usage inventory found no active production dependency. It must not be reintroduced as internal schema authority.

## Runtime expected table list policy

Runtime/admin features that report internal table status must not keep a separate drift-prone hardcoded list.

They should use one of these sources:

1. `NpdevInternalTables`
2. the generated schema-realization manifest emitted from `NpdevInternalTables`
3. a generated artifact derived from `NpdevInternalTables`

## Validation policy

A quality check must fail if:

```text
NpdevInternalTables.java is missing.
Expected Java table definition classes are missing.
Old V5001..V5014 internal migration-resource authority is reintroduced.
Hand-authored CREATE TABLE npdev_* SQL appears in active source product paths.
Future tables are accidentally introduced during current-table source-of-truth work.
Forbidden generated/source-authority output is created under source.
```

Normal Gradle-created `.gradle` and `build` folders are warnings for this check, because validation commands may create them.

## Ownership

The source owner for internal/system table structure is the Kernel dbschema package.

The generator may emit SQL from it.

Runtime may validate against generated artifacts from it.

Adapters may use table names/columns, but adapters must not become schema authorities.
