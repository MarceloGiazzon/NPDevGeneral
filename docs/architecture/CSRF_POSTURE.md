# CSRF posture (LNCH-4)

**Claim: a cross-site page cannot make a generated NPDev FinalApp perform a state-changing action,
because a request authenticated only by an ambient credential (the `npdev_jwt` session cookie) is
refused unless it also carries the `X-NPDev-Request` header, which only same-origin script can add.**

`npdev_jwt` is an HttpOnly, SameSite=Lax cookie set on jwt-mode login and OAuth sign-in
(`LoginController`, `OAuthGoogleController`). It exists so a top-level page navigation (which can
carry no custom header) still authenticates, and it is the ONLY credential after an OAuth sign-in.
`JwtBearerAuthFilter` therefore treats it as ambient: for `POST`/`PUT`/`PATCH`/`DELETE` it answers
`403 {"error":"csrf_header_required"}` unless `X-NPDev-Request` is present. Safe methods
(`GET`/`HEAD`/`OPTIONS`/`TRACE`) and requests authenticated by an `Authorization: Bearer` header are
unaffected. Every first-party page sends the header on every call. The rest of this document
explains why the header-based credentials below were never CSRF-exposed.

## Why the claim holds

CSRF works by exploiting a credential the browser sends *on its own*, without the page's own
JavaScript asking it to: a session cookie, or (historically) an `Authorization: Basic` header a
browser has cached and will replay automatically for the same realm. An attacker's page, on a
completely different origin, submits a form or fetch to the target app; the browser attaches the
ambient credential; the target app can't tell the difference between that forged request and a
real one the legitimate user meant to send.

Every authentication mode a generated FinalApp supports is header-based, and every one of those
headers is something only the legitimate page's own JavaScript can attach, never the browser
automatically:

- `Authorization: Bearer <jwt>` -- `JwtBearerAuthFilter`
- `X-Api-Key` / `X-API-Key` -- the generated `RuntimeApiKeyAuthFilter`
- `X-Super-User-Key` -- `SuperUserCredentialAuthFilter` (ControlPanel)

None of these is a cookie. None is sent by the browser unless the calling page's own script reads
a stored token (`localStorage`, in practice) and sets the header explicitly on the request it
constructs. A forged cross-site `<form>` POST or `<img>`-style GET cannot set a custom header at
all; a cross-site `fetch()`/`XHR` *can* set one, but only if the attacker's script already has the
token to put there -- which means CSRF was never the vector, token theft (XSS, a leaked log, a
compromised client) was, and that is a different, already-tracked threat class (see
[`NPDEV_BOX_OBJECT_THREAT_MODEL.md`](../security/NPDEV_BOX_OBJECT_THREAT_MODEL.md) and LNCH-4's token-revocation
work, which exists precisely to bound the blast radius of a stolen token).

## What backs the claim, structurally (not just by argument)

- **The cookie is gated by a custom header (SEC-1).** `JwtBearerAuthFilter` requires
  `X-NPDev-Request` (`JwtBearerAuthFilter.CSRF_HEADER`) when the `npdev_jwt` cookie is the credential
  on an unsafe method. A cross-site form or link cannot add it, and a cross-site `fetch()` that adds
  it needs a CORS preflight that `CorsConfig`/`DevCorsPreflightFilterConfig` never grant (credentials
  are never allowed). Pinned by `JwtBearerAuthFilterCsrfTest`.
- **No server-side session.** Nothing in `NPDevRuntimeHost` calls
  `HttpServletRequest.getSession(...)`, constructs an `HttpSession`, or calls
  `HttpServletResponse.addCookie(...)`; the only cookie is the stateless `npdev_jwt`, issued through
  `ResponseCookie` and expired by `POST /api/auth/logout` (SEC-2).
- **No Spring Security on the classpath.** `NPDevRuntimeHost/build.gradle.template` does not
  depend on `spring-boot-starter-security`, so there is no default session-cookie/CSRF-filter
  machinery a misconfiguration could silently leave half-enabled.
- **CORS explicitly denies credentialed cross-origin requests.** The dev-only CORS filter
  (`DevCorsPreflightFilterConfig`, `@Profile("dev")` -- absent entirely outside that profile) sets
  `Access-Control-Allow-Credentials: false` unconditionally. Even if a browser *did* hold an
  ambient credential (it doesn't, per above), cross-origin credentialed requests are refused by
  policy.
- **Guarded by `scripts/hygiene/check-csrf-posture.ps1`.** A structural regression check: it fails
  if a future change introduces `HttpSession`/`addCookie`/`spring-boot-starter-security` into
  `NPDevRuntimeHost`, or if `JwtBearerAuthFilter` reads cookies (`getCookies(`) without
  `CSRF_HEADER`, so this document's claim cannot silently go stale.

## What this claim does *not* cover

- **CORS misconfiguration** (an overly permissive `Access-Control-Allow-Origin` in a *non-dev*
  deployment) is a distinct risk from CSRF -- it is about *response readability*, not ambient
  credentials -- and is not addressed by this document. Production CORS configuration is the
  deploying operator's responsibility today; a hardened default is tracked separately (LNCH-7).
- **Token theft** (XSS, a compromised client, a leaked log) is not CSRF and is not mitigated by
  this posture. It is mitigated by short JWT expiry, HTTPS-only transport (LNCH-7), and the
  revocation counter (LNCH-4, `token_version` / `POST .../revoke-sessions`).
- **The ControlPanel's browser UI**, being a normal static page that reads a key from
  `localStorage` and sets `X-Super-User-Key` on its own requests, follows the identical
  header-based, non-ambient pattern -- covered by the same argument above.
