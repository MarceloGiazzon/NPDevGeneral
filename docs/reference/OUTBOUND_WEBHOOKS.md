# Outbound webhooks (INT-4 / INT-5)

A model can push an event to an outside system (an ERP, Zapier, Slack) without writing Java. A rule
calls `webhook.post`; the platform queues a signed delivery durably, a background worker sends it
with backoff, and an operator can see every delivery and replay it. The user's save is never held up
by the receiver, and a receiver that is down for hours still gets the event.

(For calling an outside API and using its answer inside a flow, see
[HTTP_CONNECTORS.md](HTTP_CONNECTORS.md). Webhooks are fire-and-forget pushes.)

## Declaring it

```json
{
  "capabilities": [
    { "name": "webhook", "type": "WebhookCapability", "adapter": "webhook-http",
      "operations": [ { "name": "post" } ] }
  ],
  "flows": [
    {
      "name": "PushNewOrdersToErp",
      "expose": "internal",
      "runAs": "system",
      "trigger": { "event": "WidgetOrderPlaced" },
      "steps": [
        {
          "name": "build",
          "type": "map",
          "select": {
            "url": "http://127.0.0.1:9099/hooks/orders",
            "event": "WidgetOrderPlaced",
            "orderId": "$input.id",
            "status": "$input.status"
          },
          "output": "payload"
        },
        {
          "name": "post",
          "type": "capabilityCall",
          "capability": "webhook",
          "operation": "post",
          "args": [ "$payload" ],
          "output": "result"
        }
      ]
    }
  ]
}
```

`url` is routing metadata and is removed from the delivered body; every other mapped field is the JSON
body. `event` (or the triggering event name) becomes the `X-Npdev-Webhook-Event` header. The event
(`WidgetOrderPlaced` above) is an ordinary `events[]` entry emitted by a flow step or published through
`POST /api/v1/events/publish`.

## Operator configuration

| Property (environment variable) | Meaning |
|---|---|
| `npdev.webhook.http.destinations` (`NPDEV_WEBHOOK_HTTP_DESTINATIONS`) | `host\|ENV_VAR;host2\|ENV_VAR2`: the receiver hosts that may be called and, per host, the NAME of the environment variable holding the shared HMAC secret. Empty means every delivery is denied (fail-closed). |
| `npdev.webhook.http.requestTimeoutMs` | Per-attempt deadline, default 15000. |
| `npdev.webhook.http.worker.intervalMs` / `initialDelayMs` | Queue drain cadence, defaults 5000 / 10000. |

Example: `NPDEV_WEBHOOK_HTTP_DESTINATIONS=erp.example.com|NPDEV_WEBHOOK_ERP_SECRET` and
`NPDEV_WEBHOOK_ERP_SECRET=<shared secret>`. A host that is not listed, or whose secret variable is
missing, is refused when the rule fires: `post` returns `WEBHOOK_EGRESS_DENIED_HOST_NOT_ALLOWED`,
`WEBHOOK_EGRESS_DENIED_NO_ALLOWLIST` or `WEBHOOK_EGRESS_DENIED_NO_SECRET` and nothing is queued. The
host and secret are checked again at send time, since configuration can change while a delivery waits.
Redirects are never followed.

## What the receiver gets

`POST <url>` with `Content-Type: application/json` and the body described above, plus:

| Header | Value |
|---|---|
| `X-Npdev-Webhook-Signature` | `sha256=` + lowercase hex HMAC-SHA256 of the raw request body, keyed with the shared secret. |
| `X-Npdev-Webhook-Delivery` | The delivery id (a UUID). Identical on every retry and on redelivery: de-duplicate on it. |
| `X-Npdev-Webhook-Event` | The event name, when known. |

Verify by recomputing the HMAC over the exact bytes received (before any JSON re-serialisation) and
comparing in constant time. Answer with any 2xx to acknowledge.

## Retries

A delivery that gets a transport error, HTTP 429 or 5xx is retried; any other non-2xx (for example 400)
is permanent and goes straight to `FAILED`.

| Failed attempts so far | Wait before the next attempt |
|---|---|
| 1 | 30 seconds |
| 2 | 2 minutes |
| 3 | 10 minutes |
| 4 | 1 hour |
| 5 | 6 hours |
| 6 | none: the delivery becomes `FAILED` (a dead letter) |

Status values: `PENDING` (queued or waiting for its next attempt), `SENDING` (claimed by a worker; a row
stuck there for more than 10 minutes, for example after a crash, returns to `PENDING`), `DELIVERED`,
`FAILED`. Several app instances can share the table; an optimistic claim makes sure only one sends.

## Operator endpoints (ADMIN role)

| Endpoint | What it does |
|---|---|
| `GET /api/admin/webhook-deliveries?status=PENDING&limit=50` | Delivery log, newest first (limit up to 200): id, host, url, event, status, attempt count, HTTP status, last error, timestamps. Never includes the payload. |
| `GET /api/admin/webhook-deliveries/{id}/payload` | The JSON body of one delivery (it may contain personal data). |
| `POST /api/admin/webhook-deliveries/{id}/redeliver` | Puts a `FAILED` or `DELIVERED` delivery back in the queue with the SAME id: 200, or 409 when it is already queued or sending, 404 when unknown. |

All three answer 503 when the app's model does not bind `webhook-http`. The table is
`npdev_webhook_delivery`; the platform creates and upgrades it by itself.

## Limits

- The payload is whatever the rule maps; keep it small, there is no size cap beyond the database's.
- Logs carry delivery ids, hosts and HTTP statuses, never payloads or secrets.
- Delivery is at-least-once: a receiver can see the same id twice (a retry after a lost acknowledgement,
  or a redelivery). De-duplicate on `X-Npdev-Webhook-Delivery`.

## Receiving webhooks from a provider (INT-6)

The other direction: an outside system (a payment provider, a Git host, a shop) calls
`POST /api/hooks/{path}` with no NPDev credential. The door is the `inbound` block of the capability it serves.
The call is accepted only when its signature verifies against a secret read from an environment variable
(`inbound.hmacSecretEnvVar`, by default `NPDEV_WEBHOOK_<PATH upper-cased, - as _>_SECRET`). A verified delivery
publishes `inbound.eventName` (resuming a flow parked on `awaitEvent`, and starting every flow whose `trigger.event` names
that event), starts the flow whose `trigger` is `{"webhook": "<capability name>"}` with the mapped payload, or both.
`path` defaults to the capability name; a pack-qualified capability must set it, because the URL a provider posts to
never depends on pack composition.

```json
{
  "capabilities": [
    { "name": "stripe",
      "inbound": {
        "signature": { "scheme": "stripe-v1" },
        "idempotencyKey": "id",
        "fieldMapping": { "orderId": "data.object.metadata.orderId", "amount": "data.object.amount_received" } } }
  ],
  "flows": [
    { "name": "RecordStripePayment", "trigger": { "webhook": "stripe" }, "steps": [ "..." ] }
  ]
}
```

| `signature.scheme` | Provider | Header value | Signed bytes |
|---|---|---|---|
| `npdev` (default) | NPDev's own outbound webhooks | `X-Npdev-Webhook-Signature: [sha256=]<hex>` | body |
| `hmac-sha256-hex` | GitHub (`prefix: "sha256="`) | hex HMAC-SHA256 after the prefix | body |
| `hmac-sha256-base64` | Shopify | base64 HMAC-SHA256 | body |
| `stripe-v1` | Stripe | `t=<unix>,v1=<hex>[,v1=<hex>]`; any `v1` may match | `t + "." + body` |

- `idempotencyKey` is a dot-path into the body or `$header.<Name>` (for example
  `$header.X-GitHub-Delivery`). A key already processed successfully answers 200
  `{"ok":true,"duplicate":true}` and runs nothing; the key is recorded only after the event or flow
  succeeded, so a provider retry of a failed attempt still runs.
- Bodies may be JSON objects or `application/x-www-form-urlencoded`.
- `stripe-v1` defaults its header to `Stripe-Signature` and its tolerance to 300 seconds.
- The triggered flow runs synchronously inside the provider's request (providers wait about 10 s, then
  retry): keep it short. A flow that fails answers 500 `WEBHOOK_FLOW_FAILED`.
- Rejections are 401 with `WEBHOOK_SIGNATURE_MISSING`, `WEBHOOK_SIGNATURE_INVALID`,
  `WEBHOOK_SIGNATURE_EXPIRED` (genuine `stripe-v1` signature outside the tolerance) or
  `WEBHOOK_SECRET_NOT_CONFIGURED`; an unknown `path` is 404.
