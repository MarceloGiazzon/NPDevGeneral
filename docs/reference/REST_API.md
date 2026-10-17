# REST API for integrators

Every generated app serves a REST API for its own model, and a machine-readable description of it,
built from the live model (so it is always current and follows a `/model-reload`).

| URL | What |
|---|---|
| `/v3/api-docs` | OpenAPI 3.0.3 JSON: every concept, query, flow and aggregate, with JSON schemas. Import it into Postman, Insomnia or a client generator. |
| `/swagger-ui/index.html` | Interactive page over the same document. Paste an API key in the box at the top; "Try it out" sends it as `X-Api-Key`. The key stays in your browser only. |

Both URLs are served without authentication and describe the schema only (no data). To switch them
off in production, start the app with `NPDEV_API_DOCS_ENABLED=false` (property
`npdev.api.docs.enabled`): both then answer 404.

## Authenticating

Send `X-Api-Key: <key>` (issue one under `/api/admin/credentials`, see
[INTEGRATION_KEYS.md](INTEGRATION_KEYS.md)) or `Authorization: Bearer <jwt>` in a JWT-mode app.

## Route families

| Route | Notes |
|---|---|
| `GET /api/concepts/{Concept}` | List. Returns `{content, page, size, totalElements, sort, direction, filter, where, asOf}`. |
| `POST /api/concepts/{Concept}` | Create; body is the concept's fields. |
| `GET/PUT/DELETE /api/concepts/{Concept}/{id}` | Read, update, delete one record (id is a uuid). Delete answers 204. |
| `POST /api/concepts/{Concept}/batch` | `{"operation":"delete"\|"setField","ids":[...],"field":"...","value":...}` (at most 500 ids); per-id outcomes in `results`. |
| `GET /api/queries/{Query}` | Declared query. Declared parameters are required query-string values. Returns `{query, aggregate, rows, total, offset, limit}` (aggregate queries return `rows`, `concept`, `groupBy`). |
| `POST /api/v1/flows/{Flow}/execute` | Run a flow with the input schema shown in the spec. 200 done, 202 waiting for an event, 422 failed. |
| `GET /api/v1/runtime/aggregate/{Aggregate}/{rootId}`, `POST .../{Aggregate}` | Load / commit an aggregate tree. |
| `GET /api/public/concepts/{Concept}` and `/{id}` | Anonymous read of concepts that declare `access.public`; only the allow-listed fields. |

### List parameters

`page` (zero-based, default 0), `size` (1 to 100, default 20), `sort` (a field), `direction`
(`asc`/`desc`), `filter` (free-text substring search across fields), `asOf` (`yyyy-MM-dd`, only for
`temporal` concepts), and `where`: clauses separated by `;` or `,`, each `field:op:value` with `op`
one of `eq`, `ne`, `contains`, `gt`, `gte`, `lt`, `lte` (`field=value` means `eq`), for example
`where=status:eq:OPEN;total:gte:100`.

Errors from the concept routes are `{ "code": "...", "message": "..." }`.
