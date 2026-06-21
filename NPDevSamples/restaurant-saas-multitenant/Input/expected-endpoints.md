# Expected Endpoints

## Expected REST surfaces

- `GET /api/flows`
- `GET /api/admin/model/export`
- `GET /api/admin/model/metadata`
- `GET /api/audit`

## Expected tenant-oriented CRUD paths

The generated app should expose `/api/` CRUD-style routes for tenant-owned concepts and enough `GET ` evidence surfaces to inspect tenant-shaped runtime behavior. Routes use snake_case, derived from
the concept's table name (e.g. `StaffMember` -> `/api/staff_members`, `DiningOrder` -> `/api/dining_orders`), not the bare lowercased concept name.

## Platform tenant-lifecycle admin surfaces

These are independent of the generated CRUD routes above and govern the platform's own
authenticated-tenant identity, not this sample's app-modeled `Tenant` concept:

- `GET /api/admin/tenants`, `POST /api/admin/tenants`
- `POST /api/admin/tenants/{tenantId}/disable`, `POST /api/admin/tenants/{tenantId}/enable`
- `GET /api/admin/credentials`, `POST /api/admin/credentials`
- `POST /api/admin/credentials/{credentialId}/revoke`
- `GET /api/me` -- resolves the caller's own authenticated identity (`actorId`, `tenantId`, `roles`)
