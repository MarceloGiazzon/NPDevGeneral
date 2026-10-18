# Self-service password reset (LNCH-4 P1)

Built on LNCH-11's `mail` capability: a public request endpoint emails a single-use token to the
account's email on file, a public confirm endpoint consumes it and sets a new password. Admin-forced
reset (no email needed) remains available in ControlPanel for accounts/apps that can't use this.

## Endpoints

Both are `@ConditionalOnProperty(npdev.auth.mode=jwt)`, like `LoginController`, and are explicitly
exempted from `JwtBearerAuthFilter`'s bearer-token requirement (`com.finalexec.config.JwtBearerAuthFilter`)
the same way `/api/auth/login` and `/api/auth/bootstrap-admin` are — self-service reset is, by
definition, something an unauthenticated caller must be able to start.

```
POST /api/auth/password-reset/request
{ "username": "ada", "tenantId": "dev" }
→ 200 { "ok": true, "message": "If that account exists, a password reset email has been sent." }
```

Always returns the same response — unknown username, inactive account, no email on file, or no
`mail` capability bound all look identical from the outside, so this can't be used to enumerate
accounts. A matching token (32 random bytes, base64url) is generated, its SHA-256 hash + a 30-minute
expiry stored in the identity pack's `PasswordResetToken` table (resolved from the compiled model, so
`identity_v1_password_reset_tokens` in a real app; an app that does not compose the concept gets the
generic response and a logged warning), and an email is dispatched through the bound `mail` capability (`CapabilityRegistry.has("mail")`). If no `mail` capability is
bound, the request returns the generic response before any token is generated or stored.

```
POST /api/auth/password-reset/confirm
{ "token": "...", "newPassword": "...", "tenantId": "dev" }
→ 200 { "ok": true }                              (valid, unused, unexpired token)
→ 400 { "error": "invalid_or_expired_token" }     (unknown/expired/already-used token)
→ 400 { "error": "invalid_request" }              (blank token or password)
→ 400 { "error": "password_too_short", "minLength": 8 }   (or password_too_long / password_too_common; SEC-3 policy)
→ 503 { "error": "identity_pack_not_composed" }   (no PasswordResetToken concept in the model)
```

On success: the credential table's password hash is overwritten (same `npdev.auth.login.*`
configurable table/column properties `LoginController` uses), the token is marked used
(single-use), and `identity_users.token_version` is bumped — invalidating every JWT already minted
under the old password, mirroring `ControlPanelTenantUsersController`'s admin-forced reset.

## Requirements for an app to use this

1. **`packs: ["identity"]`** — `PasswordResetToken` is a new concept in the identity pack
   (`id`, `userId` → `User` cascade, `tokenHash` unique, `expiresAt`, `usedAt`), schema-realized
   into `identity_v1_password_reset_tokens` the same way `identity_users` etc. already are.
2. **A populated `identity_users.email`** — previously always `NULL` at every registration path
   (`BootstrapAdminController`, `CreateUserController`, `ControlPanelAdminUserController`); all
   three now accept an optional `email` field and thread it through
   `IdentityProvisioning.insertIdentityUser` (a 7-arg overload that takes `email`; a 6-arg overload
   delegates with `email=null`, so nothing already calling it breaks). An account with no email on
   file can't use self-service reset — same treatment as an unknown username, and the fallback is
   ControlPanel's admin-forced reset.
3. **A declared and bound `mail` capability** — e.g.
   `{"name":"mail","type":"EmailCapability","operations":["send"]}` bound to `mail-inproc` (dev) or
   `mail-smtp` (real send), per `docs/reference/EMAIL_NOTIFICATIONS.md`. Without one, `requestReset` still
   returns the generic 200, it just never sends anything — a silent no-op, not an error, since
   plenty of apps won't want/need self-service reset.

`ControlPanelTenantUsersController`'s existing admin-forced reset (`PUT
/api/admin/tenants/{tenantId}/users/{username}/password`) also gained a best-effort notification
email (same `mail`-capability-bound check, silently skipped if unbound or no email on file) — an
admin overwriting someone's password now tells them, when the app can.

## The page and the "Forgot password?" link (SEC-4)

Every jwt-mode app also gets `static/reset-password.html` (`ResetPasswordPageEmitter`) and a
"Forgot password?" link under the Sign in button of `login.html`. The page has two panes: ask for a
reset link by username (always shows the same generic confirmation), then set the new password from
the mailed link (`?token=<code>` opens the second pane directly) or by typing the code ("I have a
code"). It sends the `X-NPDev-Request` header (SEC-1) on both calls.

**Production must set the link base explicitly**:
`npdev.auth.password-reset.link-base-url=https://<host>/reset-password.html?token=` — the emailed link is
that value followed by the token. Never derive it from the request's `Host` header: that enables
reset-link poisoning. With the property unset the mail carries only the bare code, which the user
types into the page's "I have a code" pane.

## Verification

- `PasswordResetControllerTest` — H2-backed hermetic test (mirrors
  `IdentityAwareContextResolverTest`'s hand-rolled `SingleConnectionUrlDataSource` pattern) with a
  real `InProcMailCapabilityAdapter` wired through the real `CapabilityRegistry`/
  `RegistryCapabilityDispatcher`: request→email→confirm round trip, reused-token rejection,
  expired-token rejection, unknown-token rejection, and all three "stays generic, sends nothing"
  cases (unknown user, no email on file, no mail capability bound).
- `IdentityPackResolutionTest` — extended to assert the new `PasswordResetToken` concept compiles
  with its `User` bond resolved, using the real shipped pack file (not an inline copy).
- Live end-to-end: a scratch sample (`packs:["identity"]` + `Usuario` credential concept bonded to
  `identity::User` + `mail`/`mail-smtp`, JWT auth mode, generated RSA keypair) proved the full
  round trip against a real booted app — bootstrap an admin with an email, log in, request a
  reset, retrieve the real token from a real SMTP send via MailHog, confirm the reset, verify the
  old password now fails and the new one works, and verify a JWT minted *before* the reset is
  rejected afterward (`token_version` bump). **Caught a real bug this way**: `JwtBearerAuthFilter`
  didn't exempt the two new endpoints, so every request 401'd with `missing_bearer_token` before
  reaching the controller at all — fixed alongside the two existing chicken-and-egg exemptions
  (`login`, `bootstrap-admin`).
