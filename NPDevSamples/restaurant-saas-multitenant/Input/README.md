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
