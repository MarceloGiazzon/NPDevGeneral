# NPDev React UI

This workspace hosts the React authoring/workbench UI for NPDev and targets the runtime API contract under `/api/v1`.

Current boundary:

- Canonical operator UI: `/npdev-ui/`
- React workbench route: `/npdev-ui-react/`
- React status: `internal-workbench`
- Boundary metadata: `ui-boundary.json`

The React app includes:

- `#/workbench` for the internal React workbench
- `#/authoring/*` for the authoring-oriented route shell
- guided model/config editors
- validation, preview, import/export, semantic graph, and diagnostics workspaces
- synchronized raw JSON power mode

## Commands

Run from `D:\WorkSpace\NPDev_General\NPDevEditor\ui-react`:

```powershell
npm ci
npm run dev
npm test
npm run build
npm run build:templates
.\build-templates.ps1
```

`npm run build:templates` builds React and copies `dist` into the Generator template resources.

`build-templates.ps1` is the deterministic packaging path used by Gradle/package scripts:

- runs `npm ci`
- runs `npm run build`
- copies `dist/*` into Generator static React template resources
- verifies `index.html`, `assets/app.js`, and `assets/app.css`

The Generator projects those assets into runtime static files under `/npdev-ui-react/`.

## Promotion Rule

The runtime-served `/npdev-ui/` surface is canonical for this cycle. `/npdev-ui-react/` remains an internal workbench until `ui-boundary.json` is explicitly changed and the release evidence covers the promotion.

## Notes

- React UI authentication uses `X-Api-Key`.
- API paths are pinned to `/api/v1`.
- Do not store API keys or environment-local secrets in committed files.
