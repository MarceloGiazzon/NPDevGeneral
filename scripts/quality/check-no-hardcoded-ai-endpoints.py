#!/usr/bin/env python3
"""No AI vendor host outside the catalogue (AIP-5, step 5.3).

WHY THIS EXISTS
---------------
Before AIP-1 the same AI vendors were hard-coded in eight places -- the generated-app adapter's
factories, the Manager's Prompter profiles, the bench, the review pack, a launcher script that also
carried a leaked key. Each drifted on its own, and none honoured the key store or the spend guard.
AIP-1..4 moved every one of them onto the catalogue (`NPDevCli/ai-providers.json`). A zero that
nothing defends goes back up: the next script that "just needs to call Groq" will paste
`https://api.groq.com/...` and bypass all of it.

WHAT IT CHECKS
--------------
Every tracked file (`git ls-files`) is searched for a vendor host: every non-loopback host in the
catalogue's `baseUrl`s, plus `extraHosts` from `scripts/policy/ai-endpoint-policy.json` (vendors the
catalogue does not ship). A hit fails unless the file matches one of the policy's `allowedPaths`
(the catalogue, tests, captured Manager fixtures, docs, ledger). The host list is read, never
repeated here.

USAGE
    python scripts/quality/check-no-hardcoded-ai-endpoints.py [--json] [--self-test]

Exit 0 = clean. Exit 1 = a vendor host outside the allowed places.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parents[2]
CATALOGUE = "NPDevCli/ai-providers.json"
POLICY = "scripts/policy/ai-endpoint-policy.json"
LOOPBACK = {"localhost", "127.0.0.1", "0.0.0.0", "::1"}


def vendor_hosts(catalogue: dict, policy: dict) -> list[str]:
    hosts = {urlparse(p.get("baseUrl") or "").hostname for p in catalogue.get("providers", [])}
    return sorted((hosts - LOOPBACK - {None}) | set(policy.get("extraHosts", [])))


def findings(files: dict[str, str], hosts: list[str], policy: dict) -> list[dict]:
    """`files` maps a repo-relative path to its text."""
    allowed = [re.compile(entry["pattern"]) for entry in policy.get("allowedPaths", [])]
    out = []
    for path, text in sorted(files.items()):
        if any(p.search(path) for p in allowed):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for host in hosts:
                if host in line:
                    out.append({"file": path, "line": number, "host": host})
    return out


def tracked_texts(root: Path) -> dict[str, str]:
    names = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True).stdout
    texts = {}
    for name in names.decode("utf-8").split("\0"):
        path = root / name
        if not name or not path.is_file() or path.stat().st_size > 4_000_000:
            continue
        try:
            texts[name] = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue  # binary
    return texts


def self_test(hosts: list[str], policy: dict) -> bool:
    planted = {
        "scripts/tools/call_groq.py": "URL = 'https://api.groq.com/openai/v1/chat/completions'\n",
        "NPDevManager/src/extra.rs": 'const U: &str = "https://api.together.xyz/v1";\n',
    }
    allowed = {
        "NPDevCli/tests/test_x.py": "'https://api.groq.com/openai/v1'\n",
        "NPDevKernel/adapters/x/src/test/java/XTest.java": "\"https://api.anthropic.com/v1\"\n",
        "docs/AI_PROVIDERS.md": "api.groq.com\n",
        "NPDevCli/ai-providers.json": "https://api.groq.com/openai/v1\n",
    }
    hits = findings({**planted, **allowed}, hosts, policy)
    flagged = {h["file"] for h in hits}
    ok = flagged == set(planted)
    print(f"self-test: {'OK' if ok else 'FAILED'} -- flagged {sorted(flagged)}, expected {sorted(planted)}")
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    catalogue = json.loads((REPO / CATALOGUE).read_text(encoding="utf-8"))
    policy = json.loads((REPO / POLICY).read_text(encoding="utf-8"))
    hosts = vendor_hosts(catalogue, policy)
    if "api.groq.com" not in hosts:
        print(f"FAIL: {CATALOGUE} no longer lists api.groq.com -- update the self-test's planted host")
        return 1
    if args.self_test:
        return 0 if self_test(hosts, policy) else 1

    hits = findings(tracked_texts(REPO), hosts, policy)
    if args.json:
        print(json.dumps({"hosts": hosts, "findings": hits}, indent=2))
    for hit in hits:
        print(f"  {hit['file']}:{hit['line']}: {hit['host']}")
    if hits:
        print(f"FAIL: {len(hits)} AI vendor host(s) outside {CATALOGUE}. Reach a vendor through the "
              f"catalogue (npdev_ai_client / ExternalAiCatalogue), or -- for a test, fixture or prose -- "
              f"see allowedPaths in {POLICY}.")
        return 1
    print(f"OK: no AI vendor host outside the catalogue ({len(hosts)} hosts checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
