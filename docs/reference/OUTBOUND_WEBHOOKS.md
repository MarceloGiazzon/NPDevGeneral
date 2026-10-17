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
    { "name": "webhook", "type": "WebhookCapability",
      "operations": [ { "name": "post", "sideEffects": "external" } ] }
  ],
  "bindings": [ { "capability": "webhook", "adapter": "webhook-http" } ],
  "orchestrationRules": [
    {
      "name": "PushNewOrdersToErp",
      "trigger": { "type": "event", "event": "WidgetOrderPlaced" },
      "actions": [
        {
          "type": "callCapability",
          "capability": "webhook",
          "operation": "post",
          "map": {
            "url": "http://127.0.0.1:9099/hooks/orders",
            "event": "WidgetOrderPlaced",
            "orderId": "$event.id",
            "status": "$event.status"
          }
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
