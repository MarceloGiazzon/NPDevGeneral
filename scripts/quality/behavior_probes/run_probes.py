#!/usr/bin/env python3
"""ENF-1: table-driven behaviour probes against a booted dsl-conformance-max app.

check-dsl-coverage.py proves a DSL feature appears in some model; this proves the runtime OBEYS it.
A probe file is JSON:

    {"id": "invariant-rejects-negative", "keywords": ["concept.invariants"],
     "description": "...", "steps": [
        {"name": "create", "method": "POST", "path": "/api/widget_orders", "body": {...},
         "expect": {"status": [200, 201], "json": {"$.id": {"exists": true}}},
         "save": {"orderId": "$.id"}}]}

Placeholders in path, body and header values:
    ${name}        a value saved by an earlier step in the same probe
    ${uuid}        a fresh UUID           ${rand}   a fresh 8-hex-digit token (unique per use)
Expectations:
    status   an int or a list of ints
    json     {"$.a.b[0].c": <literal | {"exists": bool} | {"matches": regex} | {"not": literal}
                              | {"in": [..]} | {"gt": n} | {"distinctFrom": "savedName"} | {"len": n}>}
    text     a substring the raw body must contain      notText   one it must NOT contain
A probe that exposes a REAL defect carries "knownFailure": "<ledger id>": it counts as green only while
it still fails (status "known-failure"); once the defect is fixed it turns red ("now passes -- remove
knownFailure") so the marker can never outlive the bug. Never weaken an assertion to make a probe pass.
A probe with "requires": ["superUser"] is skipped (status "skipped", not a failure) when no SUPERUSER key is supplied;
one with "requires": ["jdbc"] is skipped unless the run declares a physical-database store (--storage jdbc, or env
NPDEV_PROBE_STORAGE=jdbc; run-behavior-probes.ps1 -StorageVariant h2local sets it) -- REG-295: the default
dsl-conformance-max app runs on the tenant-keyed InMemory store, where a JDBC-only defect cannot be reached.
A step marked "always": true (or, equivalently, "stopOnFail": false) is cleanup: it runs even after an earlier step failed (and its own status is not
checked unless it carries an `expect`). `text`/`notText` accept a string or a list and substitute placeholders.
`expect` may be omitted on a setup step (any status is accepted). A probe step may set
"auth": "none" to send no API key, or "apiKey": "${savedKey}" to act as another identity (REP-1: a restricted
key issued by an earlier step); `expect.headers` ({"Content-Type": "application/pdf"}) checks response-header
substrings. LIVE-1: a step may set "auth": "superUser" (sends X-Super-User-Key from env NPDEV_PROBE_SUPER_USER_KEY)
and "reloadRevision": {"file": "<f>.json", "revision": "<name>"} (the body becomes {"modelPath": <scratch copy of the
sample's Input/ with that revision's addField/removeField ops applied>}, see model-revisions/); json rule op
{"contains": x} checks list/string/dict membership. A step may carry "retry": {"attempts": N, "delaySeconds": S}: it is re-sent until its expectations hold (for asynchronous effects such as a
scheduler tick); the last attempt's result is the one reported. Json rule ops also include {"secondsFromNow": [lo, hi]} (an ISO timestamp lo..hi seconds ahead) and a `[??k=v]` path selector
that returns every matching array element (count them with {"len": n}). A step may send "multipart": {"fields": {...}, "file": {"field",
"filename", "contentType", "content" | "repeatBytes"}} instead of a JSON body. ENF-1 batch C: a probe with
"requires": ["restart"] and "restartRevision": {"file", "revision"} spans TWO boots (run-behavior-probes.ps1
-RestartRevisionVariant): --phase beforeRestart runs its steps not marked "phase": "afterRestart" and writes saved
values/failures to --state-file; the app is regenerated from that revision (--materialize-revision) into the same
Output, so App/data survives, and rebooted without a wipe; --phase afterRestart runs the rest and gives the verdict.
Any other run skips it. REG-308: a step may carry "concurrent": {"copies": N, "statuses": {"200": 1, "409": N-1}}: the
step's request is sent N times at once from N threads released together, and the multiset of answers must match
"statuses" exactly (status -> how many copies got it) -- the way to prove a check-then-act window is closed, which no
sequential step can. Exit code is 0 only when every probe is green.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_.-]+)\}")


def resolve_path(doc, path: str):
    """Minimal JSONPath: $.a.b[0].c ; returns (found, value). `[?key=value]` selects the first array
    element whose `key` equals `value` (string compare), e.g. $.concepts[?conceptName=WidgetOrder].declaredLevel;
    several `&`-joined conditions must all hold: $.items[?concept=X&fieldPath=state&value=Draft].order."""
    if path in ("$", ""):
        return True, doc
    cur = doc
    for part in re.findall(r"\[\?[^\]]*\]|[^.\[\]]+|\[\d+\]", path.lstrip("$").lstrip(".")):
        if part.startswith("[??"):
            # [??k=v&k2=v2] selects EVERY matching element (a list), so {"len": n} can count them
            conds = [c.partition("=") for c in part[3:-1].split("&")]
            if not isinstance(cur, list):
                return False, None
            cur = [item for item in cur if isinstance(item, dict) and all(str(item.get(k)) == w for k, _, w in conds)]
        elif part.startswith("[?"):
            conds = [c.partition("=") for c in part[2:-1].split("&")]
            if not isinstance(cur, list):
                return False, None
            matches = [item for item in cur if isinstance(item, dict)
                       and all(str(item.get(k)) == w for k, _, w in conds)]
            if not matches:
                return False, None
            cur = matches[0]
        elif part.startswith("["):
            index = int(part[1:-1])
            if not isinstance(cur, list) or index >= len(cur):
                return False, None
            cur = cur[index]
        else:
            if not isinstance(cur, dict) or part not in cur:
                return False, None
            cur = cur[part]
    return True, cur


def _string_occurs(node, value: str) -> bool:
    if isinstance(node, str):
        return node == value
    if isinstance(node, list):
        return any(_string_occurs(v, value) for v in node)
    if isinstance(node, dict):
        return any(_string_occurs(v, value) for v in node.values())
    return False


def materialize_revision(sample_dir: Path | None, revision_file: str, revision: str, out_dir: Path | None = None) -> str:
    """LIVE-1: build a second revision of the sample's model WITHOUT touching the checked-in one. The
    sample's Input/ directory (model + the fragments/packs/contexts it composes from, all relative to
    model.json) is copied to a scratch directory (or `out_dir`) and model.json there gets the revision's
    ops applied (`addField` / `removeField` on a named concept; ENF-1 batch C: `renameField` {field, to,
    renamedFrom?, dropUid?} and `renameConcept` {to, renamedFrom?, dropUid?}, which keep the uid unless
    told to drop it). Returns the scratch model.json path -- what /model-reload reads, and whose parent
    a restart revision regenerates from. The revision file lives in <sample>/model-revisions/ and is a
    delta, so it can never go stale against a model.json other work keeps editing."""
    import shutil
    import tempfile
    if sample_dir is None:
        raise RuntimeError("a reloadRevision step needs --sample-dir (or a probes dir inside a sample)")
    spec = json.loads((sample_dir / "model-revisions" / revision_file).read_text(encoding="utf-8"))
    ops = spec["revisions"][revision]
    scratch = out_dir if out_dir is not None else Path(tempfile.mkdtemp(prefix=f"npdev-live1-{revision}-"))
    shutil.copytree(sample_dir / "Input", scratch, dirs_exist_ok=True)
    model_path = scratch / "model.json"
    model = json.loads(model_path.read_text(encoding="utf-8"))
    for op in ops:
        concept = next(c for c in model["concepts"] if c["name"] == op["concept"])
        if op["op"] == "addField":
            concept["fields"].append(op["field"])
        elif op["op"] == "removeField":
            concept["fields"] = [f for f in concept["fields"] if f["name"] != op["field"]]
        elif op["op"] in ("renameField", "renameConcept"):
            target = concept if op["op"] == "renameConcept" else next(f for f in concept["fields"] if f["name"] == op["field"])
            old = target["name"]
            target["name"] = op["to"]
            target.pop("renamedFrom", None)
            if op.get("renamedFrom"):
                target["renamedFrom"] = old
            if op.get("dropUid"):
                target.pop("uid", None)
            # a renamed concept that something else still names would compose into a broken model
            others = [c for c in model["concepts"] if c is not concept] + [v for k, v in model.items() if k != "concepts"]
            if op["op"] == "renameConcept" and _string_occurs(others, old):
                raise RuntimeError(f"renameConcept {old!r}: the model still references it elsewhere")
        else:
            raise RuntimeError(f"unknown revision op {op['op']!r}")
    model_path.write_text(json.dumps(model, indent=2), encoding="utf-8")
    return str(model_path)


class Probe:
    def __init__(self, base_url: str, api_key: str, spec: dict, source: str,
                 super_key: str = "", sample_dir: Path | None = None, storage: str = "",
                 phase: str = "", prior: dict | None = None) -> None:
        self.base_url = base_url.rstrip("/")
        # ENF-1 batch C: "beforeRestart" / "afterRestart" when run-behavior-probes.ps1 -RestartRevisionVariant
        # drives a restart probe across two boots ("" = an ordinary one-boot run); `prior` is what the
        # beforeRestart phase left in the state file for this probe (saved values, failures, steps).
        self.phase = phase
        self.prior = prior or {}
        self.api_key = api_key
        # LIVE-1: the SUPERUSER key (X-Super-User-Key) and the sample directory a model-revision step reads.
        self.super_key = super_key
        self.sample_dir = sample_dir
        # REG-295: which concept store the booted app runs on ("jdbc" or "inmemory"; "" = undeclared), so a
        # probe with "requires": ["jdbc"] can be skipped on the InMemory default run instead of failing falsely.
        self.storage = (storage or "").strip().lower()
        self.spec = spec
        self.source = source
        # REP-1: restricted identities provisioned by run-behavior-probes.ps1 (Add-ProbeIdentities) arrive as
        # NPDEV_PROBE_KEY_<NAME> and are visible to every probe as ${key_<name>} (e.g. ${key_alice}); a probe that
        # names one the run did not provision fails loudly with "unknown placeholder".
        self.saved: dict[str, object] = {
            "key_" + name[len("NPDEV_PROBE_KEY_"):].lower(): value
            for name, value in os.environ.items() if name.startswith("NPDEV_PROBE_KEY_") and value
        }
        # the restart phase's own values win over the env keys only for names the env did not provide
        self.saved = {**(self.prior.get("saved") or {}), **self.saved}
        self.last_headers: dict[str, str] = {}
        # TRUTH-1: every request the probe made, as the AUTHORED route (placeholders unresolved, so the
        # record is stable run to run) plus the status that came back. `npdev truth` maps these routes
        # onto concepts to decide which concepts a passing probe actually exercised.
        self.steps: list[dict] = list(self.prior.get("steps") or [])

    def subst(self, value):
        if isinstance(value, str):
            def repl(m):
                name = m.group(1)
                if name == "uuid":
                    return str(uuid.uuid4())
                if name == "rand":
                    return uuid.uuid4().hex[:8]
                if name not in self.saved:
                    raise KeyError(f"unknown placeholder ${{{name}}}")
                return str(self.saved[name])
            whole = PLACEHOLDER.fullmatch(value)
            if whole and whole.group(1) in self.saved:
                return self.saved[whole.group(1)]  # keep native type (number, bool, object)
            return PLACEHOLDER.sub(repl, value)
        if isinstance(value, list):
            return [self.subst(v) for v in value]
        if isinstance(value, dict):
            return {k: self.subst(v) for k, v in value.items()}
        return value

    def build_multipart(self, mp: dict, boundary: str) -> bytes:
        crlf = "\r\n"
        chunks: list[bytes] = []
        for key, val in (mp.get("fields") or {}).items():
            chunks.append(f'--{boundary}{crlf}Content-Disposition: form-data; name="{key}"{crlf}{crlf}{self.subst(val)}{crlf}'.encode("utf-8"))
        if mp.get("file"):
            f = mp["file"]
            payload = b"x" * int(f["repeatBytes"]) if f.get("repeatBytes") else str(self.subst(f.get("content", ""))).encode("utf-8")
            head = (f'--{boundary}{crlf}Content-Disposition: form-data; name="{f.get("field", "file")}"; filename="{f.get("filename", "a.bin")}"{crlf}'
                    f'Content-Type: {f.get("contentType", "application/octet-stream")}{crlf}{crlf}')
            chunks.append(head.encode("utf-8") + payload + crlf.encode("utf-8"))
        chunks.append(f"--{boundary}--{crlf}".encode("utf-8"))
        return b"".join(chunks)

    def call(self, step: dict):
        method = step.get("method", "GET").upper()
        url = self.base_url + self.subst(step["path"])
        headers = {"Accept": "application/json"}
        if step.get("auth") == "superUser":
            # LIVE-1: admin endpoints (POST .../metadata-hotswap/model-reload) need the SUPERUSER key, not the business key.
            if not self.super_key:
                raise RuntimeError("step needs auth=superUser but NPDEV_PROBE_SUPER_USER_KEY is not set")
            headers["X-Super-User-Key"] = self.super_key
        elif step.get("auth") != "none":
            # REP-1: a step may act as a RESTRICTED identity by naming its own key ("apiKey": "${aliceKey}",
            # a key an earlier step issued via POST /api/admin/credentials); default stays the run's ADMIN key.
            headers["X-Api-Key"] = str(self.subst(step["apiKey"])) if step.get("apiKey") else self.api_key
        for key, val in (step.get("headers") or {}).items():
            headers[key] = self.subst(val)
        data = None
        if "reloadRevision" in step:
            # LIVE-1: {"reloadRevision": {"file": "live1-add-field.json", "revision": "addField"}} -- materialise
            # that revision of the sample's model (see materialize_revision) and POST its path as the reload body.
            rev = step["reloadRevision"]
            step = {**step, "body": {"modelPath": materialize_revision(self.sample_dir, rev["file"], rev["revision"])}}
        if "multipart" in step:
            # {"multipart": {"fields": {"k": "v"}, "file": {"field": "file", "filename": "a.pdf", "contentType":
            # "application/pdf", "content": "text or ${placeholder}", "repeatBytes": 0}}} -- one multipart/form-data
            # body; "repeatBytes": n makes the file n bytes of "x" (for size-limit probes) instead of "content".
            boundary = "----enf1" + uuid.uuid4().hex
            data = self.build_multipart(step["multipart"], boundary)
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        elif "body" in step:
            data = json.dumps(self.subst(step["body"])).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as resp:
                self.last_headers = {k.lower(): v for k, v in resp.headers.items()}
                return resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as err:
            self.last_headers = {k.lower(): v for k, v in err.headers.items()}
            return err.code, err.read().decode("utf-8", "replace")

    def as_list(self, value) -> list[str]:
        """expect.text / notText: one string or a list, placeholders substituted."""
        if value is None:
            return []
        return [str(self.subst(v)) for v in (value if isinstance(value, list) else [value])]

    @staticmethod
    def seconds_from_now_in_range(value, bounds) -> bool:
        """{"secondsFromNow": [lo, hi]}: an ISO-8601 timestamp that lies lo..hi seconds after the moment of the check."""
        try:
            stamp = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
            if stamp.tzinfo is None:
                stamp = stamp.replace(tzinfo=timezone.utc)
            delta = (stamp - datetime.now(timezone.utc)).total_seconds()
            return float(bounds[0]) <= delta <= float(bounds[1])
        except (ValueError, TypeError, IndexError):
            return False

    def check_json(self, doc, rules: dict) -> list[str]:
        problems = []
        for path, want in rules.items():
            path = self.subst(path)
            found, got = resolve_path(doc, path)
            want = self.subst(want) if not isinstance(want, dict) else want
            if isinstance(want, dict) and len(want) == 1:
                op, arg = next(iter(want.items()))
                if op == "exists":
                    if found != bool(arg):
                        problems.append(f"{path}: exists={found}, wanted {arg}")
                elif not found:
                    problems.append(f"{path}: missing")
                elif op == "matches" and not re.search(arg, str(got)):
                    problems.append(f"{path}: {got!r} !~ /{arg}/")
                elif op == "not" and got == self.subst(arg):
                    problems.append(f"{path}: is {got!r}, must not be")
                elif op == "in" and got not in arg:
                    problems.append(f"{path}: {got!r} not in {arg}")
                elif op == "gt" and not (isinstance(got, (int, float)) and got > arg):
                    problems.append(f"{path}: {got!r} not > {arg}")
                elif op == "contains" and (not hasattr(got, "__contains__") or self.subst(arg) not in got):
                    problems.append(f"{path}: {got!r} does not contain {arg!r}")
                elif op == "secondsFromNow" and not self.seconds_from_now_in_range(got, arg):
                    problems.append(f"{path}: {got!r} is not within {arg} seconds from now")
                elif op == "len" and (not hasattr(got, "__len__") or len(got) != arg):
                    problems.append(f"{path}: length {len(got) if hasattr(got, '__len__') else '?'} != {arg}")
                elif op == "distinctFrom" and got == self.saved.get(arg):
                    problems.append(f"{path}: {got!r} equals saved {arg!r}, wanted distinct")
                elif op not in ("exists", "matches", "not", "in", "gt", "len", "distinctFrom", "contains", "secondsFromNow"):
                    problems.append(f"{path}: unknown operator {op}")
            elif not found:
                problems.append(f"{path}: missing, wanted {want!r}")
            elif got != want:
                problems.append(f"{path}: {got!r} != {want!r}")
        return problems

    def step_problems(self, step: dict, label: str, status: int, body: str) -> list[str]:
        """Every unmet expectation of one executed step (empty list = the step passed)."""
        failures: list[str] = []
        doc = None
        try:
            doc = json.loads(body) if body.strip() else None
        except ValueError:
            pass
        expect = step.get("expect")
        if expect is not None:
            want = expect.get("status")
            if want is not None and status not in (want if isinstance(want, list) else [want]):
                failures.append(f"{label}: status {status}, wanted {want}; body {body[:300]!r}")
            for text in self.as_list(expect.get("text")):
                if text not in body:
                    failures.append(f"{label}: body lacks {text!r}; body {body[:300]!r}")
            for text in self.as_list(expect.get("notText")):
                if text in body:
                    failures.append(f"{label}: body must not contain {text!r}")
            # REP-1: expect.headers = {"Content-Type": "application/pdf"} -- each value must be a substring
            for name, fragment in (expect.get("headers") or {}).items():
                got = getattr(self, "last_headers", {}).get(name.lower(), "")
                if str(self.subst(fragment)) not in got:
                    failures.append(f"{label}: header {name} is {got!r}, wanted it to contain {fragment!r}")
            if "json" in expect:
                failures += [f"{label}: {p}" for p in self.check_json(doc, expect["json"])]
        return failures

    def call_concurrent(self, step: dict, label: str) -> list[str]:
        """REG-308: send `step` `copies` times at once and check how many copies got each status."""
        spec = step["concurrent"]
        copies = int(spec["copies"])
        expected = {int(code): int(count) for code, count in spec["statuses"].items()}
        if sum(expected.values()) != copies:
            return [f"{label}: concurrent statuses {spec['statuses']} do not add up to {copies} copies"]
        gate = threading.Barrier(copies)
        outcomes: list[object] = [None] * copies

        def send(slot: int) -> None:
            try:
                gate.wait(timeout=30)
                outcomes[slot] = self.call(step)[0]
            except Exception as exc:  # noqa: BLE001 - reported below, never raised across the thread boundary
                outcomes[slot] = exc

        threads = [threading.Thread(target=send, args=(slot,)) for slot in range(copies)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        errors = [str(o) for o in outcomes if isinstance(o, Exception)]
        if errors:
            return [f"{label}: concurrent copy failed: {errors[0]}"]
        actual: dict[int, int] = {}
        for status in outcomes:
            actual[status] = actual.get(status, 0) + 1
        self.steps.append({"name": step.get("name"), "method": step.get("method", "GET").upper(),
                           "path": step["path"], "status": sorted(actual)[0], "concurrent": copies})
        if actual != expected:
            return [f"{label}: expected {dict(sorted(expected.items()))} statuses across {copies} concurrent copies, "
                    f"got {dict(sorted(actual.items()))}"]
        return []

    def skipped(self, reason: str) -> dict:
        return {"id": self.spec["id"], "source": self.source, "keywords": self.spec.get("keywords", []),
                "description": self.spec.get("description", ""), "knownFailure": None, "status": "skipped",
                "failures": [reason], "steps": []}

    def run(self) -> dict:
        failures: list[str] = []
        requires = self.spec.get("requires") or []
        # LIVE-1: "requires": ["superUser"] -- a probe that can only run where a SUPERUSER key exists (a
        # physical-database app; the default InMemory dsl-conformance-max issues none) is SKIPPED, loudly and
        # in the report, when the run has no key -- never silently green and never a false failure.
        if "superUser" in requires and not self.super_key:
            return self.skipped("needs NPDEV_PROBE_SUPER_USER_KEY (run scripts/quality/run-behavior-probes.ps1 -LiveFieldVariant)")
        # ENF-1 batch C: "requires": ["restart"] -- the probe spans two boots around a regeneration from its
        # "restartRevision"; only run-behavior-probes.ps1 -RestartRevisionVariant (--phase) can drive that.
        if "restart" in requires and not self.phase:
            return self.skipped("needs two boots around a model revision (run scripts/quality/run-behavior-probes.ps1 "
                                "-RestartRevisionVariant)")
        # REG-295: "requires": ["jdbc"] -- the probe exercises the physical-database store path; skipped (loudly,
        # never silently green) unless the run says it booted on one.
        if "jdbc" in requires and self.storage != "jdbc":
            return self.skipped(f"needs a JDBC-backed app (storage is {self.storage or 'undeclared'}; run "
                                "scripts/quality/run-behavior-probes.ps1 -StorageVariant h2local)")
        unknown = [r for r in requires if r not in ("superUser", "jdbc", "restart")]
        if unknown:
            return {**self.skipped(""), "status": "failed", "failures": [f"unknown requires {unknown} (known: superUser, jdbc, restart)"]}
        if ("restart" in requires) != bool(self.spec.get("restartRevision")):
            return {**self.skipped(""), "status": "failed",
                    "failures": ["a probe declares \"restartRevision\" exactly when it \"requires\" \"restart\""]}
        failures += list(self.prior.get("failures") or [])
        for index, step in enumerate(self.spec["steps"], 1):
            # a restart probe's steps run before the restart unless marked "phase": "afterRestart"
            if self.phase and step.get("phase", "beforeRestart") != self.phase:
                continue
            if failures and not (step.get("always") or step.get("stopOnFail") is False):
                continue  # an earlier step failed: only cleanup steps ("always": true) still run
            label = f"step {index} ({step.get('name', step.get('path'))})"
            if "concurrent" in step:
                failures += self.call_concurrent(step, label)
                continue
            retry = step.get("retry") or {}
            attempts = max(1, int(retry.get("attempts", 1)))
            try:
                for attempt in range(attempts):
                    status, body = self.call(step)
                    if attempt + 1 < attempts and self.step_problems(step, label, status, body):
                        time.sleep(float(retry.get("delaySeconds", 5)))
                        continue
                    break
            except Exception as exc:  # noqa: BLE001 - a probe reports, never crashes the run
                failures.append(f"{label}: {exc}")
                break
            self.steps.append({"name": step.get("name"), "method": step.get("method", "GET").upper(),
                               "path": step["path"], "status": status})
            doc = None
            try:
                doc = json.loads(body) if body.strip() else None
            except ValueError:
                pass
            failures += self.step_problems(step, label, status, body)
            for name, path in (step.get("save") or {}).items():
                found, value = resolve_path(doc, path)
                if found:
                    self.saved[name] = value
                else:
                    failures.append(f"{label}: cannot save {name}: {path} not in response; body {body[:200]!r}")
        if self.phase == "beforeRestart":
            # the verdict waits for the afterRestart phase; hand it everything this boot learned
            return {"id": self.spec["id"], "source": self.source, "status": "pending-restart", "failures": failures,
                    "steps": self.steps, "knownFailure": self.spec.get("knownFailure"),
                    "saved": {k: v for k, v in self.saved.items() if not k.startswith("key_")}}
        known = self.spec.get("knownFailure")
        if known and failures:
            status = "known-failure"
        elif known:
            status, failures = "failed", [f"knownFailure {known} is set but the probe now passes -- remove the marker"]
        else:
            status = "passed" if not failures else "failed"
        return {"id": self.spec["id"], "source": self.source, "keywords": self.spec.get("keywords", []),
                "description": self.spec.get("description", ""), "knownFailure": known,
                "status": status, "failures": failures, "steps": self.steps}


def load_probes(directory: Path, only: str) -> list[tuple[dict, str]]:
    probes = []
    for path in sorted(directory.glob("*.json")):
        if only and only not in path.name:
            continue
        doc = json.loads(path.read_text(encoding="utf-8"))
        for spec in (doc["probes"] if "probes" in doc else [doc]):
            probes.append((spec, path.name))
    return probes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--probes-dir", required=True)
    parser.add_argument("--report")
    parser.add_argument("--only", default="")
    parser.add_argument("--sample-dir", default="",
                        help="LIVE-1: the sample directory a reloadRevision step reads (default: the probes dir's parent)")
    parser.add_argument("--sample-id", default="",
                        help="recorded in the report so `npdev truth` can attribute it to the right app")
    parser.add_argument("--storage", default=os.environ.get("NPDEV_PROBE_STORAGE", ""),
                        help="REG-295: the concept store the app booted on (jdbc | inmemory); probes with "
                             "\"requires\": [\"jdbc\"] are skipped unless this is jdbc (default: env NPDEV_PROBE_STORAGE)")
    parser.add_argument("--phase", choices=["beforeRestart", "afterRestart"], default="",
                        help="ENF-1 batch C: run only the restart probes (\"requires\": [\"restart\"]), and only "
                             "their steps for this side of the restart; needs --state-file")
    parser.add_argument("--state-file", default="",
                        help="what the beforeRestart phase writes and the afterRestart phase reads back")
    parser.add_argument("--materialize-revision", nargs="?", const="", default=None, metavar="FILE:REVISION",
                        help="write that model revision's Input/ copy into --out-dir, print the directory and exit; "
                             "bare: the one restartRevision the selected restart probes share")
    parser.add_argument("--out-dir", default="", help="target directory of --materialize-revision")
    args = parser.parse_args()
    sample_dir = Path(args.sample_dir) if args.sample_dir else Path(args.probes_dir).resolve().parent
    if args.materialize_revision is not None:
        if args.materialize_revision:
            revision_file, _, revision = args.materialize_revision.partition(":")
        else:
            wanted = {(spec["restartRevision"]["file"], spec["restartRevision"]["revision"])
                      for spec, _ in load_probes(Path(args.probes_dir), args.only) if spec.get("restartRevision")}
            if len(wanted) != 1:
                print(f"the selected restart probes name {len(wanted)} distinct restartRevisions, need exactly 1: "
                      f"{sorted(wanted)}", file=sys.stderr)
                return 2
            revision_file, revision = wanted.pop()
        if not args.out_dir:
            print("--materialize-revision needs --out-dir", file=sys.stderr)
            return 2
        print(Path(materialize_revision(sample_dir, revision_file, revision, Path(args.out_dir))).parent)
        return 0
    if args.phase and not args.state_file:
        print("--phase needs --state-file", file=sys.stderr)
        return 2
    api_key = os.environ.get("NPDEV_PROBE_API_KEY", "")
    if not api_key:
        print("NPDEV_PROBE_API_KEY is not set", file=sys.stderr)
        return 2
    # LIVE-1: optional -- only probes with an auth=superUser / reloadRevision step need these.
    super_key = os.environ.get("NPDEV_PROBE_SUPER_USER_KEY", "")
    selected = load_probes(Path(args.probes_dir), args.only)
    state: dict = {}
    if args.phase:
        selected = [(spec, source) for spec, source in selected if "restart" in (spec.get("requires") or [])]
        if not selected:
            print("--phase: no restart probe (\"requires\": [\"restart\"]) is selected", file=sys.stderr)
            return 2
        if args.phase == "afterRestart":
            state = json.loads(Path(args.state_file).read_text(encoding="utf-8"))
    results = []
    for spec, source in selected:
        if args.phase == "afterRestart" and spec["id"] not in state:
            results.append({**Probe(args.base_url, api_key, spec, source).skipped(""), "status": "failed",
                            "failures": ["the beforeRestart phase left no state for this probe"]})
            continue
        result = Probe(args.base_url, api_key, spec, source, super_key=super_key, sample_dir=sample_dir,
                       storage=args.storage, phase=args.phase, prior=state.get(spec["id"])).run()
        if result["status"] == "pending-restart":
            state[spec["id"]] = result
            print(f"{'PHASE1' if not result['failures'] else 'PHASE1-FAIL'}  {result['id']}")
            for failure in result["failures"]:
                print(f"        {failure}")
            continue
        results.append(result)
        label = {"passed": "PASS", "known-failure": "KNOWN", "skipped": "SKIP"}.get(result["status"], "FAIL")
        print(f"{label}  {result['id']}" + (f"  (known failure {result['knownFailure']})" if result["knownFailure"] else ""))
        for failure in result["failures"]:
            print(f"        {failure}")
    if args.phase == "beforeRestart":
        # a phase-1 failure is carried into the afterRestart verdict (so knownFailure still applies), not judged here
        Path(args.state_file).parent.mkdir(parents=True, exist_ok=True)
        Path(args.state_file).write_text(json.dumps(state, indent=2), encoding="utf-8")
        print(f"{len(state)} restart probe(s) ran their beforeRestart steps; state in {args.state_file}")
        return 0
    failed = [r for r in results if r["status"] not in ("passed", "known-failure", "skipped")]
    known = [r for r in results if r["status"] == "known-failure"]
    report = {"schemaVersion": "npdev-behavior-probes-run.v1", "total": len(results), "failed": len(failed),
              "status": "passed" if results and not failed else "failed",
              # when the run FINISHED -- `npdev truth` compares it with the build's own timestamp (TRUTH-1)
              "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
              "results": results}
    if args.sample_id:
        report["sampleId"] = args.sample_id
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2), encoding="utf-8")
    skipped = [r for r in results if r["status"] == "skipped"]
    print(f"{len(results) - len(failed) - len(skipped)}/{len(results) - len(skipped)} behaviour probes passed "
          f"({len(known)} known failure(s), {len(skipped)} skipped)")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
