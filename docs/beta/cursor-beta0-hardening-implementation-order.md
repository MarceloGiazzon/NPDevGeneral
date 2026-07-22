# Cursor Beta 0 Hardening Implementation Order

1. Replace or complete JSON Schema validation.
2. Make AI schema validation execute schema files first.
3. Add the structured command request model.
4. Harden command runner policy and red-team tests.
5. Strengthen report schemas.
6. Add report schema validation gates.
7. Verify evidence manifests and hashes.
8. Add the final release check command.
9. Validate documentation entrypoints.
10. Clean stale active reports.
11. Run Windows CI final closure.

P0 release blockers must be closed before feature work or docs polish.
