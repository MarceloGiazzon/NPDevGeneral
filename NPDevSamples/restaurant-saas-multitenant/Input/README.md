# Restaurant SaaS Multi-Tenant Sample

This sample models one generated restaurant SaaS app shared by three tenants:

- Pizza House
- Sushi Bar
- Vegan Bistro

The modeling boundary is explicit tenant ownership:

- `Tenant` is the root tenant concept.
- Every tenant-owned concept carries a required `tenantRef` reference.
- The generated CRUD APIs store and return tenant-shaped rows.

This sample root follows the standard layout:

- `Input`: model, config, and sample docs
- `Output/ArtifactNP`: generated artifact tree
- `Output/App`: assembled runnable app
- `Output/RunOutput`: seed and verification evidence

## Generate The App

From `D:\WorkSpace\NPDev_General\NPDevSamples`, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restaurant-saas-multitenant\generate-restaurant-saas-sample.ps1
```

## Run The App

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restaurant-saas-multitenant\run-generated-app.ps1
```

The default runtime URL is `http://localhost:8093`.

## Populate And Verify Tenant Data

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restaurant-saas-multitenant\populate-restaurant-tenants.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restaurant-saas-multitenant\verify-restaurant-tenant-data.ps1
```

`populate-restaurant-tenants.ps1` writes its evidence files into `Output/RunOutput`.

## Demonstrate The Platform's Own Tenant Lifecycle

The two scripts above exercise this sample's app-modeled `Tenant` concept and `tenantRef`
reference field -- a business fact an app author models. Separately, and independently, the
platform itself has its own authenticated-tenant identity (`tenant_id`), enforced automatically on
every generated CRUD row regardless of what the model declares. To see that lifecycle end to end
(create a platform tenant, issue it a credential with no restart, disable/re-enable it, revoke a
credential):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\restaurant-saas-multitenant\demonstrate-platform-tenancy.ps1
```

A platform tenant created this way gets a wildcard-tenantId permission grant from generation
onward, so it can authenticate and use generated CRUD immediately -- no restart, no hand-authored
grant. The script also demonstrates that row-level data isolation (`tenant_id`) is a separate,
independently-enforced mechanism: two platform tenants both have full CRUD capability, but neither
can read, list, or write a bond reference into the other's data.

## Known Engine Note

This sample's `Input/db.definition.json` uses `H2Local` so the walkthrough above runs without an
external database. `config.json`'s top-level `database` block describes a separate, optional
`docker-postgres` deployment path that these scripts do not exercise.
