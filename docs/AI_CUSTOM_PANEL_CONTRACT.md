# AI Custom Panel Contract

A custom panel beta asset can be declarative metadata or a trusted-source UI file similar in intent to a WebPanel. The beta verifier still requires machine-readable bindings, but the user can provide real panel code.

Required fields:

- `schemaVersion`: `ai-custom-panel.v1`
- `panelId`
- `route`
- `dataSources`
- `visibleFields`
- `actions`
- `layout`
- `validationHints`
- optional `implementation`

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

Trusted-source panels use an `implementation` block:

- `mode`: `trustedSource`
- `language`: `html+javascript`, `html`, or `javascript`
- `entrypoint`: local source file inside the scenario directory

Direct inline UI code fields and external URLs still fail. Free panel code must live in tracked source files so NPDev can hash and attach evidence for the exact UI source.
