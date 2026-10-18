# AI Custom Panel Contract

CP12 supports the locked Minimal support scope for custom panels. A custom panel is declarative metadata only: it can describe safe labels, bounded layout intent, data bindings, and supported widgets, but it cannot load dynamic components, scripts, remote modules, or arbitrary HTML.

Required fields:

- `schemaVersion`: `ai-custom-panel.v1`
- `panelId`
- `route`
- `dataSources`
- `visibleFields`
- `actions`
- `layout`

Optional fields: `requiredRole`, `tenantScoped`, `metadata`, `validationHints`, `widgets`, `implementation`.

Supported layout types (`layout.type` in `schemas/ai/custom-panel.schema.json`; anything else, including `grid`, is rejected):

- `table`
- `detail`
- `form`
- `summary`
- `workflowTask`

Supported widget types:

- `text`
- `integer`
- `date`
- `email`
- `boolean`
- `table`
- `detail-card`
- `form`
- `action`
- `status`
- `workflow-status-badge`
- `procedure-result-block`

Supported metadata fields:

- `displayName`
- `description`
- `emptyStateMessage`
- `icon`: `table`, `form`, `summary`, `workflow`, or `status`
- `variant`: `default`, `compact`, or `readonly`

An `implementation` block is allowed only as mode `untrustedExtension`, pointing at a hash-pinned `html` or `js` file listed in `untrusted-extension-manifest.json`. `script`, `customHtml`, `externalUrl`, `dynamicComponent`, `componentUrl`, and equivalent dynamic behavior stay rejected. Untrusted-extension panels remain governed by the separate admission path and are not enabled broadly by CP12.

Runtime rendering must use the declarative panel contract. If panel data cannot be hydrated, the runtime returns a structured fallback block instead of crashing or attempting dynamic component loading.
