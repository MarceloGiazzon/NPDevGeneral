#!/usr/bin/env python3
"""F6-5: the browser expression engine must agree with the server engine on every shared fixture case.

`npdev-expr.js.mustache` (emitted as static/npdev-expr.js) is a JavaScript port of the server-side
ComputedExpression. Both replay ONE fixture, NPDevContract/dsl/src/test/resources/expr-parity.json: the
Java side in ComputedExpressionParityTest (the ground truth), this check for the browser side. Add a case
to the fixture when either engine's behaviour matters; the two suites then cannot drift silently.

Needs `node`. Without it the check SKIPs locally and FAILS under CI=true (CI always has node), so a
missing runtime can never turn into a silent pass in the pipeline.

    python scripts/quality/check-client-expr-parity.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "NPDevGenerator/generator/src/main/resources/npdev-templates/npdev-expr.js.mustache"
FIXTURE = ROOT / "NPDevContract/dsl/src/test/resources/expr-parity.json"

# Read from stdin by `node -`; argv[2] = engine file, argv[3] = fixture file. The engine is plain JavaScript
# in a .mustache-named file (no tags), which Node's CommonJS loader happily treats as a .js module.
RUNNER = r"""
const fs = require("fs");
const api = require(process.argv[2]);
const fx = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
const failures = [];
let count = 0;
function same(actual, expect) {
  if (expect === null) return actual === null;
  if (typeof expect === "number") return typeof actual === "number" && Math.abs(actual - expect) < 1e-9;
  return actual === expect;
}
for (const c of fx.eval) {
  count++;
  let r;
  try { r = api.evaluate(c.expr, c.vars); } catch (e) { failures.push("eval threw for '" + c.expr + "': " + e.message); continue; }
  if (!same(r, c.expect)) failures.push("eval '" + c.expr + "' expected " + JSON.stringify(c.expect) + " got " + JSON.stringify(r));
}
for (const expr of fx.parseError) {
  count++;
  let threw = false;
  try { api.parse(expr); } catch (e) { threw = true; }
  if (!threw) failures.push("parse should have failed for '" + expr + "'");
}
for (const c of fx.evalError) {
  count++;
  try { api.parse(c.expr); } catch (e) { failures.push("evalError case must parse: '" + c.expr + "'"); continue; }
  let threw = false;
  try { api.evaluate(c.expr, c.vars); } catch (e) { threw = true; }
  if (!threw) failures.push("evaluate should have failed for '" + c.expr + "'");
}
for (const c of fx.refs) {
  count++;
  const got = api.referencedFields(c.expr);
  if (JSON.stringify(got) !== JSON.stringify(c.fields)) failures.push("refs '" + c.expr + "' expected " + JSON.stringify(c.fields) + " got " + JSON.stringify(got));
}
for (const c of fx.boolean) {
  count++;
  if (api.isBooleanShaped(c.expr) !== c.shaped) failures.push("boolean shape of '" + c.expr + "' expected " + c.shaped);
}
if (failures.length) { console.log(failures.join("\n")); process.exit(1); }
console.log("browser engine agrees with the fixture on " + count + " cases");
"""


def main() -> int:
    for path in (ENGINE, FIXTURE):
        if not path.is_file():
            print(f"FAIL: {path.relative_to(ROOT)} is missing")
            return 1
    node = shutil.which("node")
    if node is None:
        if os.environ.get("CI", "").lower() == "true":
            print("FAIL: node is required to check browser/server expression parity under CI=true")
            return 1
        print("SKIP: node is not installed; browser/server expression parity not checked on this machine")
        return 0
    result = subprocess.run([node, "-", str(ENGINE), str(FIXTURE)], input=RUNNER, text=True,
                            encoding="utf-8", capture_output=True)
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        print("FAIL: the browser expression engine disagrees with the shared fixture (see lines above)")
        return 1
    print("OK: " + result.stdout.strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
