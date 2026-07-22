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
- `validationHints`
- optional `metadata`

Supported layout types:

- `table`
- `detail`
- `form`
- `summary`

Supported widget types:

- `text`
- `number`
- `status`
- `action`
- `table`

Supported metadata fields:

- `displayName`
- `description`
- `emptyStateMessage`
- `icon`: `table`, `form`, `summary`, `workflow`, or `status`
- `variant`: `default`, `compact`, or `readonly`

The Minimal support contract rejects `implementation`, `script`, `customHtml`, `externalUrl`, `dynamicComponent`, `componentUrl`, and equivalent dynamic behavior. Trusted-source panels remain governed by the separate trusted-source admission path and are not enabled broadly by CP12.

Runtime rendering must use the declarative panel contract. If panel data cannot be hydrated, the runtime returns a structured fallback block instead of crashing or attempting dynamic component loading.
