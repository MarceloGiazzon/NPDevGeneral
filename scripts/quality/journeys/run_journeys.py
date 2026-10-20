#!/usr/bin/env python3
"""JRN-1: the real-journey suite, REST half.

Drives the identity journeys of a booted jwt-mode app (NPDevSamples/journeys) over HTTP, reading the
mail the app "sent" from the SMTP sink's JSON-lines file. These are the paths an audit found broken
while every gate was green (password reset broken in every generated app, sign-out leaving the
cookie alive), so each check asserts the OUTCOME a user would see, never an implementation detail:

  signup     the Create-account link is mailed, choosing a password works, signing in works, a second
             signup for a finished account mails nothing, every outcome answers the same generic 200
  reset      request -> mailed link -> new password signs in, the old one no longer does, the link is
             single-use
  invite     an admin invites an address, the mailed link sets the password, the invitee signs in
  password   change-password needs the right old password and the new one then signs in
  signout    the cookie is expired by the response AND the old token is refused afterwards
  sessions   per-device sessions are listed, one is marked current, revoking another one kills that
             token (and only that token), revoke-others leaves only the caller

Exit 0 only when every check passed. The report (--report) is one JSON document.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ADMIN_USER = "journey-admin"
ADMIN_EMAIL = "journey-admin@example.test"
ADMIN_PASSWORD = "Admin-journey-pass-1"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # noqa: D401
        return None


OPENER = urllib.request.build_opener(NoRedirect)


class Suite:
    def __init__(self, base_url: str, mail_file: Path) -> None:
        self.base = base_url.rstrip("/")
        self.mail_file = mail_file
        self.results: list[dict] = []
        self.journey = "setup"

    # -- plumbing ---------------------------------------------------------------------------

    def call(self, method: str, path: str, body=None, token: str | None = None,
             cookie: str | None = None, user_agent: str = "journeys-suite/1") -> tuple[int, dict, object]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("User-Agent", user_agent)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        if cookie:
            request.add_header("Cookie", "npdev_jwt=" + cookie)
        try:
            response = OPENER.open(request, timeout=30)
        except urllib.error.HTTPError as failure:
            response = failure
        raw = response.read().decode("utf-8", "replace")
        try:
            parsed: object = json.loads(raw) if raw.strip() else None
        except ValueError:
            parsed = raw
        return response.status, dict(response.headers), parsed

    def check(self, name: str, passed: bool, detail: str = "") -> bool:
        self.results.append({"journey": self.journey, "check": name,
                             "status": "passed" if passed else "failed", "detail": "" if passed else detail})
        print(("  ok    " if passed else "  FAIL  ") + f"[{self.journey}] {name}" + ("" if passed else f" -- {detail}"))
        return passed

    def mail_for(self, address: str, after: int, timeout: float = 20.0) -> dict | None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            for record in self.mails()[after:]:
                if address.lower() in [t.lower() for t in record["to"]]:
                    return record
            time.sleep(0.4)
        return None

    def mails(self) -> list[dict]:
        if not self.mail_file.exists():
            return []
        return [json.loads(line) for line in self.mail_file.read_text(encoding="utf-8").splitlines() if line.strip()]

    @staticmethod
    def token_of(record: dict | None) -> str | None:
        found = re.search(r"token=([A-Za-z0-9_\-]+)", record["body"]) if record else None
        return found.group(1) if found else None

    def login(self, username: str, password: str, user_agent: str = "journeys-suite/1"):
        status, headers, body = self.call("POST", "/api/auth/login",
                                          {"username": username, "password": password}, user_agent=user_agent)
        token = body.get("token") if isinstance(body, dict) else None
        cookie_header = headers.get("Set-Cookie", "")
        cookie = re.search(r"npdev_jwt=([^;]*)", cookie_header)
        return status, token, (cookie.group(1) if cookie else None), cookie_header

    # -- journeys ---------------------------------------------------------------------------

    def setup(self) -> str | None:
        status, _, body = self.call("POST", "/api/auth/bootstrap-admin", {
            "username": ADMIN_USER, "displayName": "Journey Admin", "password": ADMIN_PASSWORD,
            "email": ADMIN_EMAIL})
        self.check("bootstrap the first admin", status == 201, f"{status} {body}")
        status, token, _, _ = self.login(ADMIN_USER, ADMIN_PASSWORD)
        self.check("admin signs in", status == 200 and bool(token), f"{status}")
        return token

    def signup(self) -> None:
        self.journey = "signup"
        address = f"new-{uuid.uuid4().hex[:8]}@example.test"
        before = len(self.mails())
        status, _, body = self.call("POST", "/api/auth/signup", {"email": address, "displayName": "New Person"})
        self.check("signup answers the generic 200", status == 200 and isinstance(body, dict) and body.get("ok") is True,
                   f"{status} {body} (404 means signup is not enabled)")
        mail = self.mail_for(address, before)
        token = self.token_of(mail)
        self.check("the link is mailed to the address", token is not None, "no mail with a token= link arrived")
        if token is None:
            return
        password = "A-brand-new-pass-1"
        status, _, body = self.call("POST", "/api/auth/password-reset/confirm", {"token": token, "newPassword": password})
        self.check("the mailed link lets the person choose a password", status == 200, f"{status} {body}")
        status, jwt, _, _ = self.login(address, password)
        self.check("the new account signs in", status == 200 and bool(jwt), f"{status}")
        before = len(self.mails())
        self.call("POST", "/api/auth/signup", {"email": address, "displayName": "Again"})
        again = self.mail_for(address, before, timeout=3.0)
        self.check("a second signup for a finished account mails nothing", again is None, "a link was mailed")
        status, _, other = self.call("POST", "/api/auth/signup", {"email": "not-an-address"})
        self.check("an invalid address gets the same generic answer", status == 200 and isinstance(other, dict)
                   and other.get("ok") is True, f"{status} {other}")

    def reset(self, admin_token: str) -> None:
        self.journey = "reset"
        username = f"reset-{uuid.uuid4().hex[:6]}"
        address = f"{username}@example.test"
        old_password = "Old-reset-pass-1"
        before = len(self.mails())
        status, _, body = self.call("POST", "/api/auth/create-user", {
            "username": username, "displayName": "Reset Person", "password": old_password, "email": address},
            token=admin_token)
        self.check("admin creates a user with an e-mail", status in (200, 201), f"{status} {body}")
        status, _, body = self.call("POST", "/api/auth/password-reset/request", {"username": username})
        self.check("reset request answers the generic 200", status == 200, f"{status} {body}")
        mail = self.mail_for(address, before)
        token = self.token_of(mail)
        self.check("the reset link is mailed", token is not None, "no mail with a token= link arrived")
        if token is None:
            return
        new_password = "Fresh-reset-pass-2"
        status, _, body = self.call("POST", "/api/auth/password-reset/confirm", {"token": token, "newPassword": new_password})
        self.check("the link sets the new password", status == 200, f"{status} {body}")
        status, jwt, _, _ = self.login(username, new_password)
        self.check("the new password signs in", status == 200 and bool(jwt), f"{status}")
        status, _, _ = self.call("POST", "/api/auth/login", {"username": username, "password": old_password})
        self.check("the old password no longer signs in", status == 401, f"{status}")
        status, _, _ = self.call("POST", "/api/auth/password-reset/confirm", {"token": token, "newPassword": "Third-reset-pass-3"})
        self.check("the link is single-use", status == 400, f"{status}")

    def invite(self, admin_token: str) -> None:
        self.journey = "invite"
        address = f"invitee-{uuid.uuid4().hex[:6]}@example.test"
        before = len(self.mails())
        status, _, body = self.call("POST", "/api/auth/invite", {"email": address, "displayName": "Invited Person"},
                                    token=admin_token)
        self.check("admin invites an address", status in (200, 201), f"{status} {body}")
        mail = self.mail_for(address, before)
        token = self.token_of(mail)
        self.check("the invitation link is mailed", token is not None, "no mail with a token= link arrived")
        if token is None:
            return
        password = "Invited-person-pass-1"
        status, _, body = self.call("POST", "/api/auth/password-reset/confirm", {"token": token, "newPassword": password})
        self.check("the invitation link sets the password", status == 200, f"{status} {body}")
        status, jwt, _, _ = self.login(address.lower(), password)
        self.check("the invitee signs in", status == 200 and bool(jwt), f"{status}")

    def password(self, admin_token: str) -> None:
        self.journey = "password"
        username = f"change-{uuid.uuid4().hex[:6]}"
        old, new = "Change-old-pass-1", "Change-new-pass-2"
        self.call("POST", "/api/auth/create-user", {"username": username, "displayName": "Changer",
                                                    "password": old}, token=admin_token)
        _, token, _, _ = self.login(username, old)
        status, _, _ = self.call("POST", "/api/auth/change-password",
                                 {"oldPassword": "wrong-old-password", "newPassword": new}, token=token)
        self.check("a wrong old password is refused", status == 400, f"{status}")
        status, _, body = self.call("POST", "/api/auth/change-password", {"oldPassword": old, "newPassword": new}, token=token)
        self.check("the right old password changes it", status == 200, f"{status} {body}")
        status, jwt, _, _ = self.login(username, new)
        self.check("the new password signs in", status == 200 and bool(jwt), f"{status}")
        status, _, _ = self.call("POST", "/api/auth/login", {"username": username, "password": old})
        self.check("the old password is refused", status == 401, f"{status}")

    def signout(self, admin_token: str) -> None:
        self.journey = "signout"
        username = f"out-{uuid.uuid4().hex[:6]}"
        password = "Signout-pass-1"
        self.call("POST", "/api/auth/create-user", {"username": username, "displayName": "Signer Out",
                                                    "password": password}, token=admin_token)
        status, token, cookie, cookie_header = self.login(username, password)
        self.check("sign-in sets the session cookie", status == 200 and bool(cookie), f"{status} {cookie_header!r}")
        status, _, _ = self.call("GET", "/api/auth/me", cookie=cookie)
        self.check("the cookie authenticates before sign-out", status == 200, f"{status}")
        status, headers, _ = self.call("POST", "/api/auth/logout", token=token, cookie=cookie)
        expiry = headers.get("Set-Cookie", "")
        expired = "npdev_jwt=" in expiry and ("Max-Age=0" in expiry or "1970" in expiry)
        self.check("sign-out answers with an expiring cookie", status == 204 and expired, f"{status} {expiry!r}")
        status, _, _ = self.call("GET", "/api/auth/me", cookie=cookie)
        self.check("the old cookie is refused after sign-out", status == 401, f"{status}")
        status, _, _ = self.call("GET", "/api/auth/me", token=token)
        self.check("the old bearer token is refused after sign-out", status == 401, f"{status}")

    def sessions(self, admin_token: str) -> None:
        self.journey = "sessions"
        username = f"dev-{uuid.uuid4().hex[:6]}"
        password = "Sessions-pass-1"
        self.call("POST", "/api/auth/create-user", {"username": username, "displayName": "Many Devices",
                                                    "password": password}, token=admin_token)
        _, laptop, _, _ = self.login(username, password, user_agent="journeys-laptop/1")
        _, phone, _, _ = self.login(username, password, user_agent="journeys-phone/1")
        _, tablet, _, _ = self.login(username, password, user_agent="journeys-tablet/1")
        status, _, body = self.call("GET", "/api/auth/sessions", token=laptop)
        rows = body.get("sessions", []) if isinstance(body, dict) else []
        self.check("three devices are listed", status == 200 and len(rows) == 3, f"{status} {body}")
        self.check("exactly one is marked current", sum(1 for r in rows if r.get("current")) == 1, f"{rows}")
        phone_row = next((r for r in rows if "phone" in (r.get("userAgent") or "")), None)
        if phone_row is None:
            self.check("the phone session is identifiable", False, f"{rows}")
            return
        status, _, body = self.call("DELETE", f"/api/auth/sessions/{phone_row['id']}", token=laptop)
        self.check("the laptop revokes the phone", status == 200, f"{status} {body}")
        status, _, _ = self.call("GET", "/api/auth/me", token=phone)
        self.check("the revoked phone token is refused", status == 401, f"{status}")
        status, _, _ = self.call("GET", "/api/auth/me", token=laptop)
        self.check("the laptop token still works", status == 200, f"{status}")
        status, _, body = self.call("POST", "/api/auth/sessions/revoke-others", token=laptop)
        fresh = body.get("token") if isinstance(body, dict) else None
        self.check("revoke-others succeeds and hands the caller a fresh token", status == 200 and bool(fresh),
                   f"{status} {body}")
        status, _, _ = self.call("GET", "/api/auth/me", token=tablet)
        self.check("the tablet token is refused after revoke-others", status == 401, f"{status}")
        status, _, _ = self.call("GET", "/api/auth/me", token=fresh)
        self.check("the caller's fresh token works after revoke-others", status == 200, f"{status}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mail-file", required=True, help="JSON-lines file written by smtp_sink.py")
    parser.add_argument("--report", help="write the JSON report here")
    args = parser.parse_args()

    suite = Suite(args.base_url, Path(args.mail_file))
    admin_token = suite.setup()
    if admin_token:
        suite.signup()
        suite.reset(admin_token)
        suite.invite(admin_token)
        suite.password(admin_token)
        suite.signout(admin_token)
        suite.sessions(admin_token)
    failed = [r for r in suite.results if r["status"] != "passed"]
    report = {"schemaVersion": "npdev-journeys-rest-result.v1", "total": len(suite.results),
              "failed": len(failed), "status": "passed" if not failed and admin_token else "failed",
              "results": suite.results}
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"{len(suite.results) - len(failed)}/{len(suite.results)} journey checks passed")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
