# Expected Diagnostics

## Expected warning surfaces

- warning messages should explain when tenant reference data is incomplete or missing
- warning output should remain diagnostic-only and not claim authenticated tenant filtering that the sample does not provide

## Expected error surfaces

- error diagnostics should identify missing required tenantId references
- error responses should remain clear when invalid tenant-shaped payloads are submitted
