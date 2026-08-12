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
  "cap": "mail",
  "op": "send",
  "args": ["$input.email", "$input.name", "$input.message"],
  "out": "$mailDelivery"
}
```

Two payload shapes are accepted:

- **Positional** (`to, subject, body[, templateVars]`) — the shape above. This is the shape a flow
  author actually needs: the DSL's `args` can only pass through existing state/input values (or a
  dotted sub-path into one, e.g. `$saved.email`), it cannot construct a literal string inline, so
  there is no way to build a single `{"to":...,"subject":...}` map from within the flow itself.
- **Single map** (`{"to":..., "subject":..., "body":..., "templateVars":{...}}`) — used when
  calling the capability programmatically (not from a flow's `args` list), e.g. a single `$input`
  ref whose value already has this shape.

Both go through `com.npdev.kernel.ports.MailPayload.parse(...)`, shared by both adapters so the
payload contract is defined once.

## Templating

`subject`/`body` may contain `${varName}` placeholders, substituted from `templateVars` via
`MailTemplateRenderer` before send/record. In the positional form, since the DSL can't compose a
literal templated string, put the already-final text directly in the `subject`/`body` refs (pull
it from a prior step's output field) rather than relying on placeholder substitution — templating
is most useful for the single-map form, where a caller controls the literal template text and
just needs field values interpolated in.

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
`smtp` compose profile (`docker compose --profile smtp up`), mirroring LNCH-14's `minio`/
`objectstore` profile. It's a dev-only catcher — mail sent through it is never actually delivered;
view it at `http://localhost:8025`. `NPDEV_MAIL_SMTP_HOST` defaults to `mailhog` (the compose
service's DNS name), so an app whose model bound `mail-smtp` reaches it automatically once the
profile is up; the app ignores the service entirely if unbound.

## Known DSL limitation

A flow's `capabilityCall` step args are pure value-refs (`$state.path`), never literal strings —
`resolveReferenceStrict`/`resolvePath` (`KernelRunner`) treat every arg the same way regardless of
its `$`-prefix. This means a flow cannot construct a natural-language subject/body with embedded
`${var}` placeholders purely from DSL syntax; either the field value itself already IS the final
text (positional form, most common), or the caller building the payload programmatically supplies
literal template text with placeholders (single-map form). Not a bug — a fair description of what
the existing flow DSL can and can't express; documented rather than worked around, since fixing it
would mean adding string-literal syntax to the DSL, well beyond this item's scope.

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
