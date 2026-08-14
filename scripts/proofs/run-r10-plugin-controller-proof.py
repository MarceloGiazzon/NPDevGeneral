#!/usr/bin/env python3
"""Prove plugin:java-controller mounts AND that D9's security.minimumRole is ENFORCED -- R10.

npdev-plugin-controller-security-enforcement: this is the fourth file in that twin-pair rule
(scripts/quality/twin-pair-registry.json), and the ONLY place this session verified D9's live
enforcement. NPDevRuntimeHost's PluginControllerSecurityConfig imports com.npdev.generated.*, so
build.gradle.template's generatedRuntimeDependentMainSources exclusion means it is compiled ONLY
inside an assembled app, never in a bare-template unit test -- per CLAUDE.md's "RuntimeHost tests
that name com.npdev.generated. never run in any gate," a JUnit test for it would be dead weight in
run-runtimehost-gate.ps1. This script is the real proof instead.

WHAT WAS MISSING BEFORE THIS
-----------------------------
`plugin:java-source` (NPDevSamples/probes/lib-probe) already proves user Java can be mounted from the
app definition directory and survive regeneration -- but only for a capability IMPLEMENTATION invoked
by reflection against an operation binding. A raw `@RestController`, served directly by Spring's own
routing, is a different shape entirely: no operation to call, no flow ever references it. The trap the
R10 card names explicitly: `runtime-supported-controllers.json`'s three enforcement points all key off
`com/finalexec/api` plus ONE fixed, per-app-identical manifest -- a plugin controller lives outside
that package by definition, so a FOURTH enforcement point (PluginControllerSecurityConfig) had to be
built, one that reads a manifest GENERATED PER APP rather than a fixed platform one.

THE TWO ASSERTIONS
-------------------
1. **The mount actually serves a request.** GET /api/plugins/admin-tools/ping with the dev API key
   returns 200 -- the author's hand-written controller, copied from the definition directory at
   generation time, is a live Spring bean answering real HTTP.
2. **minimumRole is ENFORCED, not merely declared -- D9.** GET /api/plugins/super-only/ping with the
   SAME dev API key returns 403. adminTools declares minimumRole=ADMIN, which the dev/auth.mode=none
   profile grants to every caller (CLAUDE.md's agent-proxy precedent: "ADMIN is no gate at all in dev
   apps"); superOnlyTools declares minimumRole=SUPERUSER, which that same fallback NEVER grants
   ("SUPERUSER is never in that fallback set"). The identical caller succeeding against one endpoint
   and being rejected by the other is what proves the wrapper checks the DECLARED role per mount, not
   just "is this caller authenticated at all."

Deliberately h2-local (this session's test-cadence policy): this proves the mount + security-wrapper
mechanism, not a storage dialect. The GitHub Actions PR gate's Postgres/MySQL/SqlServer matrix is the
multi-engine backstop.

USAGE
    python scripts/proofs/run-r10-plugin-controller-proof.py --port 18330 --report <path>

Exit 0 = both assertions passed. Exit 1 = at least one failed. Exit 2 = usage/setup problem.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "quality"))

# Reuse the engine proof's generate/build/boot machinery rather than writing a second copy -- see
# run-library-probe.py's own docstring for the same reasoning.
import importlib.util

_ENGINE_PROOF = Path(__file__).resolve().parent.parent / "quality" / "run-engine-app-proof.py"
_spec = importlib.util.spec_from_file_location("npdev_engine_app_proof", _ENGINE_PROOF)
_engine_proof = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_engine_proof)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=18330)
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--report", default=None)
    parser.add_argument("--probe", default=None)
    args = parser.parse_args(argv)

    root = _engine_proof._repo_root()
    probe = Path(args.probe) if args.probe else root / "NPDevSamples" / "probes" / "p7-plugin-controller"
    app_input = probe / "Input"

    build_root = Path(os.environ.get("NPDEV_BUILD_ROOT") or (root.parent / "Build"))
    work = build_root / "engine-proof" / "p7-plugin-controller"
    work.mkdir(parents=True, exist_ok=True)
    output = work / "App"
    boot_log = work / "boot.log"
    if boot_log.exists():
        boot_log.unlink()

    results: list[dict] = []
    app = None
    failure: str | None = None
    try:
        _engine_proof.generate(root, app_input, output)
        jar = _engine_proof.build(output)
        app = _engine_proof.App(jar, output, args.port, boot_log)
        app.start(args.boot_timeout)

        base = app.base()

        # 1. The mount actually serves a request -- an ADMIN-gated controller, called by a caller
        # dev/auth.mode=none grants ADMIN to automatically.
        status, body = _engine_proof.http("GET", f"{base}/api/plugins/admin-tools/ping")
        ok = status == 200 and isinstance(body, dict) and body.get("ok") is True
        results.append({
            "assertion": "admin-gated-mount-serves-a-real-request",
            "ok": ok,
            "detail": (f"status={status}; body={body!r}. The mounted AdminToolsController, copied "
                       f"from the app definition directory at generation time, must be a live Spring "
                       f"bean answering real HTTP -- not just present as source, not just compiled."),
        })

        # 2. D9: minimumRole is ENFORCED, not merely declared. Same dev-key caller, SUPERUSER-gated
        # endpoint -- must be rejected, because dev/auth.mode=none never auto-grants SUPERUSER.
        status2, body2 = _engine_proof.http("GET", f"{base}/api/plugins/super-only/ping")
        ok2 = status2 == 403
        results.append({
            "assertion": "superuser-gated-mount-rejects-the-same-caller-d9",
            "ok": ok2,
            "detail": (f"status={status2}; body={body2!r}, expected 403. The SAME dev-key caller that "
                       f"just succeeded against adminTools (minimumRole=ADMIN) must be rejected here "
                       f"(minimumRole=SUPERUSER) -- proving PluginControllerSecurityConfig's "
                       f"interceptor checks the DECLARED role per mount, genuinely rejecting an "
                       f"under-privileged caller, not merely gating on authentication."),
        })
    except SystemExit as exc:
        failure = str(exc)
        print(f"[r10-plugin-controller-proof] ABORTED: {failure}", flush=True)
    finally:
        if app is not None:
            app.stop()

    ok = failure is None and all(result["ok"] for result in results)
    report = {
        "schemaVersion": "npdev-r10-plugin-controller-proof.v1",
        "ok": ok,
        "failure": failure,
        "assertions": results,
        "bootLog": str(boot_log),
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"\n### plugin:java-controller mount + D9 security enforcement (R10): {'PASS' if ok else 'FAIL'}\n\n")
            handle.write("| assertion | result | detail |\n|---|---|---|\n")
            for result in results:
                handle.write(f"| {result['assertion']} | "
                             f"{'pass' if result['ok'] else '**FAIL**'} | {result['detail']} |\n")
            if failure:
                handle.write(f"\n**Aborted:** {failure}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
