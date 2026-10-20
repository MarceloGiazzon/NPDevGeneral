#!/usr/bin/env python3
"""JRN-1: a throwaway local SMTP sink for the journey suite.

Password reset, invitations and self-signup all send mail through the generated app's `mail`
capability. The suite binds `mail-smtp` to this sink instead of a real server, so a journey can read
the one-time link the app "mailed" without any SMTP dependency or credential.

It speaks just enough SMTP for the Jakarta Mail client (EHLO/HELO, MAIL, RCPT, DATA, RSET, NOOP,
QUIT), advertises no STARTTLS and no AUTH, and appends one JSON object per accepted message to
--out:  {"to": [...], "from": "...", "subject": "...", "body": "<decoded text>"}.
The body is MIME-decoded here, so a quoted-printable "=3D" never hides a link from a journey.

Binds 127.0.0.1 only. Stop it by terminating the process; it holds no state besides the --out file.
"""
from __future__ import annotations

import argparse
import email
import email.policy
import json
import socketserver
import threading
from pathlib import Path

_WRITE_LOCK = threading.Lock()


def _decoded_body(raw: bytes) -> tuple[str, str]:
    message = email.message_from_bytes(raw, policy=email.policy.default)
    subject = str(message.get("Subject", ""))
    parts: list[str] = []
    if message.is_multipart():
        for part in message.walk():
            if part.get_content_type() in ("text/plain", "text/html"):
                parts.append(part.get_content())
    else:
        parts.append(message.get_content())
    return subject, "\n".join(parts)


class _Handler(socketserver.StreamRequestHandler):
    def _send(self, line: str) -> None:
        self.wfile.write((line + "\r\n").encode("ascii"))
        self.wfile.flush()

    def handle(self) -> None:
        out: Path = self.server.out_path  # type: ignore[attr-defined]
        self._send("220 npdev-journeys-sink ESMTP")
        sender, recipients = "", []
        while True:
            raw = self.rfile.readline()
            if not raw:
                return
            line = raw.decode("utf-8", "replace").strip()
            verb = line.split(" ", 1)[0].upper()
            if verb in ("EHLO", "HELO"):
                self._send("250-npdev-journeys-sink")
                self._send("250 8BITMIME")
            elif verb == "MAIL":
                sender, recipients = line.partition(":")[2].strip().strip("<>"), []
                self._send("250 OK")
            elif verb == "RCPT":
                recipients.append(line.partition(":")[2].strip().strip("<>"))
                self._send("250 OK")
            elif verb == "DATA":
                self._send("354 End data with <CR><LF>.<CR><LF>")
                chunks: list[bytes] = []
                while True:
                    data_line = self.rfile.readline()
                    if not data_line or data_line in (b".\r\n", b".\n"):
                        break
                    chunks.append(data_line[1:] if data_line.startswith(b"..") else data_line)
                subject, body = _decoded_body(b"".join(chunks))
                record = {"to": recipients, "from": sender, "subject": subject, "body": body}
                with _WRITE_LOCK, out.open("a", encoding="utf-8", newline="\n") as handle:
                    handle.write(json.dumps(record) + "\n")
                self._send("250 OK queued")
            elif verb in ("RSET", "NOOP"):
                self._send("250 OK")
            elif verb == "QUIT":
                self._send("221 Bye")
                return
            else:
                self._send("502 Command not implemented")


class _Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--out", required=True, help="JSON-lines file the accepted messages are appended to")
    args = parser.parse_args()
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(b"")
    with _Server(("127.0.0.1", args.port), _Handler) as server:
        server.out_path = out_path  # type: ignore[attr-defined]
        print(f"smtp sink listening on 127.0.0.1:{args.port}, writing {out_path}", flush=True)
        server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
