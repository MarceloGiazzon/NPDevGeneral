# Email / notification primitive (LNCH-11)

A generated app can send email through a declared `mail` capability, following the same
adapter-pair pattern as persistence (`memory`/`postgres`/`repository`) and notification
(`notification-inproc`): a dev/test adapter that records deliveries in memory, and a real adapter
that sends over the wire.

## Declaring it

```json
{
  "capabilities": [
    { "name": "mail", "type": "EmailCapability", "operations": ["send"] }
  ],
  "bindings": [
    { "capability": "mail", "adapter": "mail-smtp" }
  ]
}
```

Use `adapter: "mail-inproc"` for dev/test (records deliveries in memory, no network) or
`adapter: "mail-smtp"` for a real send via Jakarta Mail. **Which adapter an app uses is a
generation-time model choice** (the `bindings` entry), the same as persistence's
`repository`/`postgres`/`memory` choice — there is no runtime env-var toggle between them, since
capability adapters resolve through the model's own compiled bindings, not through
`@ConditionalOnProperty`-style config (that pattern is reserved for ports like `FileStoreContract`
that bypass the capability-registry/binding-resolution path entirely — see `NpdevFileStoreConfig`
for that shape and `docs/DEPLOYMENT.md` for the object-store adapter that uses it).

## Calling it from a flow

```json
{
  "name": "send-welcome-email",
  "type": "capabilityCall",
  "capability": "mail",
  "operation": "send",
  "args": ["$input.email", "$input.name", "$input.message"],
  "output": "$mailDelivery"
}
```

Two payload shapes are accepted:

- **Positional** (`to, subject, body[, templateVars]`) — the shape above. This is the shape a flow
  author actually needs: the DSL's `args` pass through existing state/input values (or a dotted
  sub-path into one, e.g. `$saved.email`), and an arg that resolves to nothing is passed as a literal
  string (see "Literal subject/body text" below), but there is no way to build a single
  `{"to":...,"subject":...}` map from within the flow itself.
- **Single map** (`{"to":..., "subject":..., "body":..., "templateVars":{...}}`) — used when
  calling the capability programmatically (not from a flow's `args` list), e.g. a single `$input`
  ref whose value already has this shape.

Both go through `com.npdev.kernel.ports.MailPayload.parse(...)`, shared by both adapters so the
payload contract is defined once.

## Templating

`subject`/`body` may contain `${varName}` placeholders, substituted from `templateVars` via
`MailTemplateRenderer` before send/record. In the positional form, literal text with `${var}` placeholders can be
passed as the `subject`/`body` args and filled from a `templateVars` ref (see "Literal subject/body
text" below); templating is equally useful for the single-map form, where a caller controls the
literal template text and just needs field values interpolated in.

## Adapters

- `mail-inproc` (`NPDevKernel/adapters/mail-inproc`) — `InProcMailCapabilityAdapter`, records
  every send (rendered) in memory; `deliveries()` lets a test/gate assert on sent mail without a
  network, mirroring `notification-inproc`.
- `mail-smtp` (`NPDevKernel/adapters/mail-smtp`) — `SmtpMailCapabilityAdapter`, sends via
  Jakarta Mail (Eclipse Angus implementation). Host/port/credentials/from/starttls are supplied at
  construction time from env vars (`NPDEV_MAIL_SMTP_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD`/`_FROM`/
  `_STARTTLS`), wired in `NpdevPluginConfig.mailSmtpRuntimePluginRealizationProvider`. Constructor
  validates host/from are non-blank, but construction is lazy (only invoked if the model actually
  binds `mail` to `mail-smtp`) — same reasoning as the Postgres persistence adapter not requiring a
  `DataSource` for an in-memory-only app.

Both implement `com.npdev.kernel.ports.EmailCapability` (a typed `send(MailMessage) ->
MailSendResult` port, in addition to the generic `CapabilityAdapter` dispatch contract used by
flow steps) for callers with a direct bean reference.

## Docker Compose: MailHog SMTP catcher

Every generated app's `docker-compose.yml` includes an optional `mailhog` service gated behind the
`smtp` compose profile (`docker compose --profile smtp up`), mirroring LNCH-14's `objectstore`
profile. It's a dev-only catcher — mail sent through it is never actually delivered;
view it at `http://localhost:8025`. `NPDEV_MAIL_SMTP_HOST` defaults to `mailhog` (the compose
service's DNS name), so an app whose model bound `mail-smtp` reaches it automatically once the
profile is up; the app ignores the service entirely if unbound.

**App running on the host (`Start-App.ps1`, `bootRun`), not in compose?** Don't use the compose
profile: compose interpolates the whole file before starting any service, so even
`docker compose --profile smtp up -d mailhog` refuses to start until the app service's required
secrets (`NPDEV_AUTH_APIKEYS`, `DB_PASSWORD`, …) are set. Those `:?` checks are deliberate
fail-fast guards for a real deploy and stay. Run the catcher directly instead, and point the app at it:

```powershell
docker run -d --name mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog:v1.0.1
$env:NPDEV_MAIL_SMTP_HOST = "localhost"; $env:NPDEV_MAIL_SMTP_PORT = "1025"
```

## Literal subject/body text

An arg that does not resolve to anything in flow/procedure state is passed through as the literal
string (`KernelRunner.resolveReference`; `DefaultProcedureExecutor.callCapability` for procedures).
So the positional form can carry real template text, filled from a `templateVars` ref:

```json
"args": ["$recipient.email", "New trade proposal ${code}", "${proposerUsername} proposed ${code}.", "$trade"]
```

Avoid literal text that happens to equal a state key, or starts with `$`.

**In a procedure, `args` is an object, and its values are passed positionally in the order its
keys are declared.** The key names are yours; declare them as `to, subject, body, templateVars`
(`NPDevSamples/pigmentampas`, procedure `NotifyTradeRecipient`).

## In-app notifications (notify.send)

`mail.send` reaches people outside the app and needs SMTP. For people who are signed in, a step can
instead store a per-user notification that the shell shows behind a bell in the topbar of every page
(unread count, a list of recent items, click to follow the link and mark it read). Email stays an
explicit, separate `mail.send` step: nothing falls back from one to the other.

```json
{
  "capabilities": [ { "name": "notify", "type": "NotifyCapability", "operations": ["send"] } ],
  "bindings":     [ { "capability": "notify", "adapter": "notify" } ]
}
```

Procedure step (args is an object; they are passed positionally in declared key order):

```json
{ "name": "notify-in-app", "type": "capabilityCall", "capability": "notify", "operation": "send",
  "args": { "to": "$recipient.username", "title": "New trade ${code} from ${proposerUsername}",
            "body": "Answer it within 7 days.", "templateVars": "$trade",
            "link": "/npdev-business-ui/collab/tasks.html" },
  "target": "notified" }
```

Flow step (args is an array, result goes to `output`):

```json
{ "name": "notify-in-app", "type": "capabilityCall", "capability": "notify", "operation": "send",
  "args": ["$saved.recipientUsername", "New trade ${code}", "Answer within 7 days.", "$saved",
           "/npdev-business-ui/collab/tasks.html"],
  "output": "$notified" }
```

- Argument order is `(to, title, body, templateVars, link)`, the same convention as `mail.send`.
  `to` is a username or a list of usernames (one notification each). `${var}` placeholders in `title`
  and `body` come from `templateVars`.
- `link` must be site-relative (start with a single `/`); anything else is silently dropped.
- The tenant comes from the flow context. The result is `{ "notified": <count> }`.
- Storage: the `npdev_notification` internal table when `npdev.storage.mode=jdbc`, in memory otherwise.
- REST (always the caller's own rows): `GET /api/notifications?unreadOnly=false&limit=20`,
  `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read` (404 for anyone else's
  row), `POST /api/notifications/read-all`. The bell polls the count every 60 s while the tab is visible.
- Binding the capability is what switches the bell on (`collaboration.notifications` in the UI manifest).
- No retention yet: read rows are kept.

## Operator alerts

Independent of the `mail` capability a model declares, every generated app can e-mail its operator
when something needs attention. Set one variable and the app mails a short digest, using the SMTP
settings it already has (`npdev.mail.smtp.*`, the `NPDEV_MAIL_SMTP_*` variables):

| Environment variable | Property | Meaning |
|---|---|---|
| `NPDEV_ALERTS_MAIL_TO` | `npdev.alerts.mail-to` | Recipient(s), comma separated. Blank (default) = alerts off. |
| `NPDEV_ALERTS_BASE_URL` | `npdev.alerts.base-url` | Public URL of the app, used to build links in the mail (optional). |
| `NPDEV_ALERTS_INTERVAL_MS` | `npdev.alerts.interval-ms` | Check interval, default 300000 (5 minutes). |

What triggers a mail: a flow execution that is `FAILED`, `FAILED_PERMANENT` or `STUCK` (reported once
per execution; failures are read from the recent 100-execution window of the execution monitor, stuck
ones from the stuck queue), and the app's health leaving UP or returning to UP. Only the home tenant
is watched. The mail carries ids, flow names, status and error codes, never row data or keys. With no
recipient or no SMTP host the app logs one `operator alerts are off` line and sends nothing.

An app cannot report that its own process is down: for that, the Docker observability profile's
Prometheus/Alertmanager rule (`NPDevAppDown`, see `docs/DEPLOYMENT.md`) still applies.

## Verification

- `InProcMailCapabilityAdapterTest` / `SmtpMailCapabilityAdapterTest` (the latter against a real
  GreenMail SMTP server) — adapter-level unit tests for both payload shapes and template
  rendering.
- `MailInProcFlowIntegrationTest` — a hand-built `FlowDefinition` with a `capabilityCall` step
  dispatches through the real `CapabilityRegistry`/`RegistryCapabilityDispatcher`/`KernelRunner`
  wiring to a real `InProcMailCapabilityAdapter`, proving the templated positional-args path end
  to end without a generated app.
- Live: a temporary `mail`/`mail-smtp` capability + flow step added to `simple-contact-intake`
  (reverted after, clean `git diff`), regenerated, built, and run via `docker compose --profile
  smtp up` — a real HTTP POST triggered the flow, which sent a real email over real SMTP to the
  MailHog catcher; verified via MailHog's REST API showing the correct To/Subject/Body.
