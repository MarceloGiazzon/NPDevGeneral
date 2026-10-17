# Keys for integrations (INT-8)

Give a partner system (an ERP, a reporting tool, Zapier) its own API key, scoped by a model role,
labelled, optionally time-boxed, and revocable. Keys are random per app and per issue: there is no
fixed development key. The first admin key of an app lives in its `secrets/api-key.env`.

## Scope = a model role

A key's roles are its scope. Declare a role in the model for the integration, with only the concept
operations it needs:

```json
"roles": [
  { "name": "ErpReader", "grants": [], "concepts": { "Order": ["read"], "Customer": ["read"] } }
]
```

## Issue, list, revoke (`/api/admin/credentials`, ADMIN or SUPERUSER)

```
POST /api/admin/credentials
{ "actorId": "erp", "roles": ["ErpReader"], "label": "ERP", "expiresAt": "2026-12-31" }
-> 200 { "credentialId": "...", "apiKey": "npk_...", "label": "ERP", "expiresAtMs": 1798761600000, ... }
```

* The raw `apiKey` is shown **once**; only its SHA-256 hash is stored.
* `expiresAt` is an ISO-8601 instant (`2026-12-31T23:59:00Z`) or a date (valid through the end of that
  day, UTC). Blank means never. A past value is a 400. An expired key answers 401.
* `roles` must be `ADMIN`, `USER`, `SUPERUSER` or a role declared in the model (case-insensitive);
  anything else is a 400 `unknown role: X`. Only a SUPERUSER may issue a SUPERUSER key.
* `GET /api/admin/credentials` lists `label`, `expiresAtMs`, `expired`, `lastUsedAtMs` (refreshed at
  most once a minute) and `createdBy`, so stale keys can be found.
* `POST /api/admin/credentials/{credentialId}/revoke` revokes a key immediately.

## Tenants

A SUPERUSER administers every tenant's keys (`tenantId` in the request body). An ADMIN who is not a
SUPERUSER is pinned to its own tenant: issuing for another tenant is a 403, and list / revoke only see
and affect its own tenant's keys (another tenant's id answers `Unknown credential`).

## Where these keys work

Registry keys authenticate through the API-key filter, so use them in `auth.mode=apikey` apps with
`X-Api-Key: npk_...`. A `jwt`-mode app accepts only bearer tokens / the session cookie and answers 401
`missing_bearer_token` to `X-Api-Key`. The static `NPDEV_AUTH_API_KEYS` mapping always wins over the
registry and never expires: use the registry, not that variable, for integration keys. A key issued
before this feature has no label and never expires; its first use creates its `lastUsedAtMs`.
