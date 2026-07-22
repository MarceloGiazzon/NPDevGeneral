# Expected Behavior

## Expected user journeys

1. Generate the shared restaurant SaaS sample and verify the app boots successfully against its
   own H2Local database (`Input/db.definition.json`).
2. Populate tenant data for Pizza House, Sushi Bar, and Vegan Bistro via the generated CRUD APIs.
3. Verify that menu, table, and staff records remain visibly associated with the correct tenant
   reference (`tenantRef`, an app-modeled business field, not the platform's own isolation key).
4. Separately, exercise the platform's own tenant lifecycle: create a platform tenant, issue it a
   credential, confirm it authenticates via `/api/me`, immediately use generated CRUD with that
   credential (no restart, no hand-authored grant), confirm row isolation against a second platform
   tenant, disable/re-enable the tenant, and revoke the credential.
