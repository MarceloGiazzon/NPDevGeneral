# Expected Behavior

## Expected user journeys

1. Generate the shared restaurant SaaS sample and verify the app boots successfully against its
   own H2Local database (`Input/db.definition.json`).
2. Populate tenant data for Pizza House, Sushi Bar, and Vegan Bistro via the generated CRUD APIs.
3. Verify that menu, table, and staff records remain visibly associated with the correct tenant
   reference (`tenantRef`, an app-modeled business field, not the platform's own isolation key).
4. Separately, exercise the platform's own tenant lifecycle: create a platform tenant, issue it a
   credential, confirm it authenticates via `/api/me`, disable/re-enable the tenant, revoke the
   credential -- and confirm the platform's permission model gates a brand-new tenant out of
   generated CRUD until a grant is hand-authored for it (a confirmed, currently open gap).
