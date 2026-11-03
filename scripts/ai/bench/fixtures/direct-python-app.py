"""A known-good tiny application for the benchmark's direct arm (BENCH-1 dry run).

It is what a model would be asked to write, kept as a fixture so the whole route -- parse the reply, write the
files, boot under a cleared environment, derive the catalog from OpenAPI, run the acceptance probes -- can be tested
with no model at all. It satisfies NPDevSamples's dry-run prompt (scripts/ai/bench/fixtures/bench-900-canary-dry.json):
tasks with a mandatory title, owners with a name.

The contract it follows (NPDevSamples/ai-bench/direct-arm.json -> probeContract): PORT from the environment,
GET /v3/api-docs, REST collections under /api/<collection>, X-Api-Key with the keys in NPDEV_BENCH_ROLE_KEYS,
400 with {"error": ...} on bad input, data kept in a file-backed database.
"""
import json
import os
import re
import sqlite3
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8000"))
KEYS = set(json.loads(os.environ.get("NPDEV_BENCH_ROLE_KEYS", "{}")).values())
DB = sqlite3.connect("app.db", check_same_thread=False)
DB.execute("CREATE TABLE IF NOT EXISTS rows (collection TEXT, id TEXT, body TEXT, PRIMARY KEY (collection, id))")
DB.commit()

COLLECTIONS = {
    "tasks": {"required": ["title"], "fields": {"title": "string", "priority": "integer", "status": "string"}},
    "owners": {"required": ["name"], "fields": {"name": "string"}},
}


def openapi():
    schemas, paths = {}, {}
    for name, spec in COLLECTIONS.items():
        schema = name[:-1].capitalize()
        schemas[schema + "Request"] = {
            "type": "object", "required": spec["required"],
            "properties": {f: {"type": t} for f, t in spec["fields"].items()},
        }
        ref = {"$ref": "#/components/schemas/%sRequest" % schema}
        paths["/api/" + name] = {
            "get": {"responses": {"200": {"content": {"application/json": {"schema": {"type": "array", "items": ref}}}}}},
            "post": {"requestBody": {"content": {"application/json": {"schema": ref}}}, "responses": {"201": {}}},
        }
        paths["/api/%s/{id}" % name] = {"get": {}, "put": {}, "patch": {}, "delete": {}}
    return {"openapi": "3.0.1", "info": {"title": "bench app", "version": "1"}, "paths": paths,
            "components": {"schemas": schemas}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, status, obj):
        raw = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authorised(self):
        if self.headers.get("X-Api-Key") not in KEYS:
            self._send(401, {"error": "missing or unknown X-Api-Key"})
            return False
        return True

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return None

    def do_GET(self):
        if self.path == "/v3/api-docs":
            return self._send(200, openapi())
        m = re.match(r"^/api/([a-z]+)(?:/([^/]+))?$", self.path)
        if not m or m.group(1) not in COLLECTIONS:
            return self._send(404, {"error": "not found"})
        if not self._authorised():
            return
        collection, ident = m.groups()
        if ident is None:
            rows = DB.execute("SELECT body FROM rows WHERE collection=?", (collection,)).fetchall()
            return self._send(200, [json.loads(r[0]) for r in rows])
        row = DB.execute("SELECT body FROM rows WHERE collection=? AND id=?", (collection, ident)).fetchone()
        return self._send(200, json.loads(row[0])) if row else self._send(404, {"error": "no such row"})

    def do_POST(self):
        m = re.match(r"^/api/([a-z]+)$", self.path)
        if not m or m.group(1) not in COLLECTIONS:
            return self._send(404, {"error": "not found"})
        if not self._authorised():
            return
        spec = COLLECTIONS[m.group(1)]
        body = self._body()
        if not isinstance(body, dict):
            return self._send(400, {"error": "the body must be a JSON object"})
        missing = [f for f in spec["required"] if body.get(f) in (None, "")]
        if missing:
            return self._send(400, {"error": "missing required field(s): " + ", ".join(missing)})
        body["id"] = uuid.uuid4().hex
        DB.execute("INSERT INTO rows VALUES (?, ?, ?)", (m.group(1), body["id"], json.dumps(body)))
        DB.commit()
        return self._send(201, body)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
