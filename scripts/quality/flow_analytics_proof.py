#!/usr/bin/env python3
"""FLOW-1 live proof: seed flow executions on a booted app, then check the analytics endpoint.

Three subcommands, all against a RUNNING app (this script never builds or boots anything):

  seed     run N executions of a flow (and M human-task executions that wait, then get answered),
           so the Executions page's Analytics tab has real data to show.
  verify   fetch GET /api/v1/executions/analytics and compare EVERY number against ground truth that
           this script computes on its own from the raw traces (GET /api/v1/traces). The two share no
           code: a bug in the server-side aggregation cannot also be in this recomputation.
  tenancy  create two tenants, run flows as each, and prove tenant B cannot see tenant A (neither by
           default nor by passing tenant=A), while a SUPERUSER can name either tenant.

Definitions mirror FlowAnalytics.java's javadoc (median = middle / mean of two middles, p95 =
nearest rank, elapsed = service + waitedMs, bottleneck rank = median elapsed desc).

Run (app already up, e.g. dsl-conformance-max on port 8330):
  python scripts/quality/flow_analytics_proof.py seed   --base-url http://127.0.0.1:8330 --api-key KEY --count 200
  python scripts/quality/flow_analytics_proof.py verify --base-url http://127.0.0.1:8330 --api-key KEY
  python scripts/quality/flow_analytics_proof.py tenancy --base-url http://127.0.0.1:8330 --api-key ADMINKEY --super-key SUPERKEY
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
import uuid


def http(method: str, url: str, key: str | None = None, body: object | None = None,
         super_key: str | None = None) -> tuple[int, object]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if key:
        request.add_header("X-Api-Key", key)
    if super_key:
        request.add_header("X-Super-User-Key", super_key)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw


# ------------------------------------------------------------------------------------ seeding

def flow_payload(flow: str, i: int) -> dict:
    if flow == "SubmitExpense":
        return {"employeeEmail": f"flow1-{i}@example.test", "amount": 10 + i, "needsManagerApproval": False,
                "status": "submitted"}
    return order_payload(i)


def order_payload(i: int) -> dict:
    # Alternate small and bulk orders so the flow's branch step takes both arms.
    return {"customerEmail": f"flow1-{i}-{uuid.uuid4().hex[:6]}@example.test",
            "status": "open", "lineCount": 8 if i % 3 == 0 else 2}


def run_flow(base: str, key: str, flow: str, payload: dict) -> tuple[int, object]:
    """POST /api/flows/<flow>/execute. The request body IS the flow input (not wrapped)."""
    return http("POST", f"{base}/api/flows/{flow}/execute", key, payload)


def seed(base: str, key: str, flow: str, count: int, human: int, human_wait_ms: int, approver_key: str | None,
         quick_flow: str, quick_count: int) -> None:
    ran = 0
    for i in range(count):
        status, body = run_flow(base, key, flow, order_payload(i))
        # A flow that FAILS (capability error, validation) still wrote a trace: 4xx/5xx with an
        # executionId in the body counts as a run. Only a transport-level miss does not.
        if isinstance(body, dict) and body.get("executionId"):
            ran += 1
        elif i < 3:
            print(f"  seed {flow} #{i}: HTTP {status} {body}", file=sys.stderr)
    print(f"ran {ran}/{count} executions of {flow}")
    if ran == 0:
        raise SystemExit("no execution ran; refusing to continue")
    quick = 0
    for i in range(quick_count if quick_flow else 0):
        status, body = run_flow(base, key, quick_flow, {"sku": f"FLOW1-{i}"})
        quick += 1 if isinstance(body, dict) and body.get("executionId") else 0
    if quick_flow:
        print(f"ran {quick}/{quick_count} executions of {quick_flow}")
    if human:
        # Answering a task needs an assignee holding the task's role (WarehouseManager in
        # dsl-conformance-max): pass a key that has it as --approver-key.
        seed_human(base, key, human, human_wait_ms, answer_key=approver_key or key)


def seed_human(base: str, key: str, count: int, wait_ms: int, flow: str = "AwaitCatalogEntryApproval",
               answer_key: str | None = None) -> None:
    started = []
    for i in range(count):
        payload = {"id": str(uuid.uuid4()), "sku": f"FLOW1-{i}-{uuid.uuid4().hex[:5]}", "name": f"Flow1 entry {i}"}
        status, body = run_flow(base, key, flow, payload)
        if not (isinstance(body, dict) and body.get("status") == "WAITING_EVENT"):
            print(f"  human start #{i}: HTTP {status} {body}", file=sys.stderr)
            continue
        started.append(_execution_id(body))
    print(f"started {len(started)} human-task executions; waiting {wait_ms} ms before answering")
    time.sleep(wait_ms / 1000.0)
    answered = 0
    for index, execution_id in enumerate(started):
        # Leave the last two unanswered: they are the "waiting now" count.
        if index >= len(started) - 2:
            continue
        status, body = http("POST", f"{base}/api/tasks/{execution_id}/complete", answer_key or key, {
            "stepName": "await-catalog-approval", "outcome": "APPROVED", "values": {"comment": "ok"}})
        if status == 200:
            answered += 1
        elif answered == 0:
            print(f"  complete {execution_id}: HTTP {status} {body}", file=sys.stderr)
        # stagger the answers so the waits differ
        time.sleep(0.05)
    print(f"answered {answered} tasks; {len(started) - answered} still waiting")


def seed_expense_flow(base: str, key: str, count: int, wait_ms: int, leave_waiting: int = 2) -> None:
    """durable-workflow-demo's SubmitExpense: parks on an awaitEvent INSIDE a branch when
    needsManagerApproval is true, and is resumed by publishing ExpenseApproved on its correlationId.
    Runs `count` expenses (2 of 3 need approval), waits, then approves all but `leave_waiting`."""
    parked: list[tuple[str, str]] = []
    direct = 0
    for i in range(count):
        needs = i % 3 != 2
        status, body = run_flow(base, key, "SubmitExpense", {
            "employeeEmail": f"flow1-{i}@example.test", "amount": 10 + i, "needsManagerApproval": needs,
            "status": "submitted"})
        if not isinstance(body, dict) or not body.get("executionId"):
            print(f"  SubmitExpense #{i}: HTTP {status} {body}", file=sys.stderr)
            continue
        if body.get("status") == "WAITING_EVENT":
            parked.append((body["executionId"], body["correlationId"]))
        else:
            direct += 1
    print(f"SubmitExpense: {direct} completed directly, {len(parked)} parked on approval; waiting {wait_ms} ms")
    time.sleep(wait_ms / 1000.0)
    approved = 0
    for index, (execution_id, correlation_id) in enumerate(parked):
        if index >= len(parked) - leave_waiting:
            continue
        status, body = http("POST", f"{base}/api/events/publish", key, {
            "eventName": "ExpenseApproved", "correlationId": correlation_id,
            "payload": {"expenseId": execution_id, "approved": True, "reviewer": "flow1"}})
        if status in (200, 201, 202):
            approved += 1
        elif approved == 0:
            print(f"  publish approval {execution_id}: HTTP {status} {body}", file=sys.stderr)
        time.sleep(0.05)
    print(f"approved {approved}; {len(parked) - approved} still waiting")


def _execution_id(body: object) -> str:
    if isinstance(body, dict):
        for key in ("executionId", "traceId"):
            if body.get(key):
                return str(body[key])
        for nested in body.values():
            if isinstance(nested, dict) and nested.get("executionId"):
                return str(nested["executionId"])
    raise SystemExit(f"cannot find executionId in {body!r}")


# ------------------------------------------------------------------- independent ground truth

def fetch_all_traces(base: str, key: str, flow: str | None = None) -> list[dict]:
    traces: list[dict] = []
    offset = 0
    while True:
        url = f"{base}/api/v1/traces?limit=1000&offset={offset}"
        if flow:
            url += f"&flowName={flow}"
        status, page = http("GET", url, key)
        if status != 200 or not isinstance(page, list):
            raise SystemExit(f"GET {url} -> {status} {page!r}")
        traces.extend(page)
        if len(page) < 1000:
            return traces
        offset += 1000


def _median(values: list[int]):
    ordered = sorted(values)
    n = len(ordered)
    if n % 2:
        return ordered[n // 2]
    total = ordered[n // 2 - 1] + ordered[n // 2]
    return total // 2 if total % 2 == 0 else total / 2


def _p95(values: list[int]):
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def _is_parked(step: dict) -> bool:
    return (step.get("info") or {}).get("awaitedEventStatus") == "WAITING"


def _outcome(trace: dict) -> str:
    if trace["outcome"] == "OK":
        return "OK"
    return "WAITING" if any(_is_parked(s) for s in trace["steps"]) else "FAILED"


def ground_truth(traces: list[dict]) -> dict:
    """flow -> {executions, outcomes, durations[], steps{name -> samples}} straight from raw traces."""
    flows: dict[str, dict] = {}
    for trace in traces:
        flow = flows.setdefault(trace["meta"]["flowName"], {
            "executions": 0, "outcomes": {"OK": 0, "FAILED": 0, "WAITING": 0}, "durations": [],
            "steps": {}, "waits": [], "waiting": 0})
        flow["executions"] += 1
        outcome = _outcome(trace)
        flow["outcomes"][outcome] += 1
        if outcome == "WAITING":
            flow["waiting"] += 1
        else:
            flow["durations"].append(max(0, trace["endedAtEpochMs"] - trace["startedAtEpochMs"]))
        for step in trace["steps"]:
            acc = flow["steps"].setdefault(step["stepName"], {
                "index": step["stepIndex"], "reached": 0, "parked": 0, "service": [], "elapsed": [], "wait": []})
            acc["index"] = min(acc["index"], step["stepIndex"])
            acc["reached"] += 1
            if _is_parked(step):
                acc["parked"] += 1
                continue
            service = max(0, step["endedAtEpochMs"] - step["startedAtEpochMs"])
            waited = (step.get("info") or {}).get("waitedMs")
            acc["service"].append(service)
            acc["elapsed"].append(service + (max(0, int(waited)) if waited is not None else 0))
            if waited is not None:
                acc["wait"].append(max(0, int(waited)))
                flow["waits"].append(max(0, int(waited)))
    return flows


# ---------------------------------------------------------------------------------- comparison

class Verdict:
    def __init__(self) -> None:
        self.checks = 0
        self.failures: list[str] = []

    def eq(self, label: str, got, want) -> None:
        self.checks += 1
        same = (abs(float(got) - float(want)) < 1e-9) if isinstance(got, (int, float)) and isinstance(want, (int, float)) \
            else got == want
        if not same:
            self.failures.append(f"{label}: endpoint={got!r} ground-truth={want!r}")

    def true(self, label: str, condition: bool) -> None:
        self.checks += 1
        if not condition:
            self.failures.append(label)


def compare(analytics: dict, truth: dict, verdict: Verdict, flow_filter: str | None = None) -> None:
    flows = {f["flowName"]: f for f in analytics["flows"]}
    verdict.eq("flow set", sorted(flows), sorted(truth))
    verdict.eq("executionCount", analytics["executionCount"], sum(f["executions"] for f in truth.values()))
    for name, want in truth.items():
        got = flows.get(name)
        if got is None:
            continue
        verdict.eq(f"{name}.executions", got["executions"], want["executions"])
        for outcome, count in want["outcomes"].items():
            verdict.eq(f"{name}.byOutcome.{outcome}", got["byOutcome"][outcome], count)
        verdict.eq(f"{name}.currentlyWaiting", got["currentlyWaiting"], want["waiting"])
        verdict.eq(f"{name}.durationMs.count", got["durationMs"]["count"], len(want["durations"]))
        if want["durations"]:
            verdict.eq(f"{name}.durationMs.median", got["durationMs"]["medianMs"], _median(want["durations"]))
            verdict.eq(f"{name}.durationMs.p95", got["durationMs"]["p95Ms"], _p95(want["durations"]))
        verdict.eq(f"{name}.waitMs.count", got["waitMs"]["count"], len(want["waits"]))
        got_steps = {s["stepName"]: s for s in got["steps"]}
        verdict.eq(f"{name}.stepNames", sorted(got_steps), sorted(want["steps"]))
        ranked = []
        for step_name, acc in want["steps"].items():
            step = got_steps.get(step_name)
            if step is None:
                continue
            verdict.eq(f"{name}.{step_name}.reached", step["reached"], acc["reached"])
            verdict.eq(f"{name}.{step_name}.waiting", step["waiting"], acc["parked"])
            for label, key, samples in (("serviceMs", "service", acc["service"]),
                                        ("elapsedMs", "elapsed", acc["elapsed"]),
                                        ("waitMs", "wait", acc["wait"])):
                verdict.eq(f"{name}.{step_name}.{label}.count", step[label]["count"], len(samples))
                if samples:
                    verdict.eq(f"{name}.{step_name}.{label}.median", step[label]["medianMs"], _median(samples))
                    verdict.eq(f"{name}.{step_name}.{label}.p95", step[label]["p95Ms"], _p95(samples))
                    verdict.eq(f"{name}.{step_name}.{label}.max", step[label]["maxMs"], max(samples))
                    verdict.eq(f"{name}.{step_name}.{label}.total", step[label]["totalMs"], sum(samples))
            if acc["elapsed"]:
                ranked.append((-_median(acc["elapsed"]), -_p95(acc["elapsed"]), step_name))
        ranked.sort()
        if ranked:
            verdict.eq(f"{name}.slowestStep", got["slowestStep"], ranked[0][2])
    for outcome in ("OK", "FAILED", "WAITING"):
        verdict.eq(f"byOutcome.{outcome}", analytics["byOutcome"][outcome], sum(f["outcomes"][outcome] for f in truth.values()))
    all_waits = [w for f in truth.values() for w in f["waits"]]
    human = analytics["humanWait"]
    verdict.eq("humanWait.currentlyWaitingExecutions", human["currentlyWaitingExecutions"], sum(f["waiting"] for f in truth.values()))
    verdict.eq("humanWait.count", human["resumedWaits"]["count"], len(all_waits))
    verdict.eq("humanWait.totalMs", human["resumedWaits"]["totalMs"], sum(all_waits))
    if all_waits:
        verdict.eq("humanWait.median", human["resumedWaits"]["medianMs"], _median(all_waits))
        verdict.eq("humanWait.p95", human["resumedWaits"]["p95Ms"], _p95(all_waits))
    # Overall bottleneck order = median elapsed desc across all flows.
    expected = []
    for name, want in truth.items():
        for step_name, acc in want["steps"].items():
            if acc["elapsed"]:
                expected.append((-_median(acc["elapsed"]), -_p95(acc["elapsed"]), step_name, name))
    expected.sort()
    got_rank = [(b["flowName"], b["stepName"]) for b in analytics["bottlenecks"]]
    verdict.eq("bottleneck ranking (top 10)", got_rank, [(e[3], e[2]) for e in expected][:10])
    verdict.eq("rank numbers", [b["rank"] for b in analytics["bottlenecks"]], list(range(1, len(got_rank) + 1)))


def verify(base: str, key: str, flow: str | None, tenant: str | None = None, quiet: bool = False) -> Verdict:
    url = f"{base}/api/v1/executions/analytics"
    params = []
    if flow:
        params.append(f"flow={flow}")
    if tenant:
        params.append(f"tenant={tenant}")
    if params:
        url += "?" + "&".join(params)
    status, analytics = http("GET", url, key)
    verdict = Verdict()
    verdict.eq("analytics HTTP status", status, 200)
    if status != 200:
        return verdict
    truth = ground_truth(fetch_all_traces(base, key, flow))
    compare(analytics, truth, verdict, flow)
    verdict.true("not truncated", analytics.get("truncated") is False)
    if not quiet:
        print_summary(analytics, verdict)
    return verdict


def print_summary(analytics: dict, verdict: Verdict) -> None:
    print(f"executions={analytics['executionCount']} byOutcome={analytics['byOutcome']} humanWait={analytics['humanWait']}")
    for flow in analytics["flows"]:
        print(f"  {flow['flowName']}: executions={flow['executions']} slowestStep={flow['slowestStep']} "
              f"duration median={flow['durationMs']['medianMs']} p95={flow['durationMs']['p95Ms']}")
        for step in flow["steps"]:
            print(f"      {step['stepName']:<26} reached={step['reached']:<4} median={step['elapsedMs']['medianMs']} "
                  f"p95={step['elapsedMs']['p95Ms']} wait.median={step['waitMs']['medianMs']} waiting={step['waiting']}")
    print("top bottlenecks: " + ", ".join(f"#{b['rank']} {b['flowName']}.{b['stepName']}={b['elapsedMs']['medianMs']}"
                                          for b in analytics["bottlenecks"][:5]))
    print(f"{verdict.checks} comparisons against raw /api/v1/traces ground truth, {len(verdict.failures)} mismatches")
    for failure in verdict.failures[:25]:
        print("  MISMATCH " + failure)


# ----------------------------------------------------------------------------------- tenancy

def tenancy(base: str, admin_key: str, super_key: str | None, flow: str, per_tenant: int,
            explicit: dict[str, tuple[str, str]] | None = None, super_api_key: str | None = None) -> int:
    """explicit = {"A": (tenantId, apiKey), "B": (...)}: statically configured keys (the in-memory
    storage mode has no credential registry, so the admin API answers 503 there). Without it the
    tenants and credentials are created through /api/admin/*."""
    suffix = uuid.uuid4().hex[:6]
    tenants = {"A": f"flow1-a-{suffix}", "B": f"flow1-b-{suffix}"}
    keys: dict[str, str] = {}
    if explicit:
        tenants = {label: pair[0] for label, pair in explicit.items()}
        keys = {label: pair[1] for label, pair in explicit.items()}
    for label, tenant_id in ([] if explicit else tenants.items()):
        status, body = http("POST", f"{base}/api/admin/tenants", admin_key, {"tenantId": tenant_id, "displayName": tenant_id})
        if status not in (200, 201, 409):
            raise SystemExit(f"create tenant {tenant_id}: {status} {body}")
        status, body = http("POST", f"{base}/api/admin/credentials", None if super_key else admin_key, {
            "tenantId": tenant_id, "actorId": f"actor-{label.lower()}", "roles": ["ADMIN"] if flow == "SubmitExpense" else ["ADMIN", "WarehouseManager"]},
                           super_key=super_key)  # roles must be ones the model declares  # credential issuing is SUPERUSER-gated
        if status not in (200, 201) or not isinstance(body, dict) or not body.get("apiKey"):
            raise SystemExit(f"issue credential for {tenant_id}: {status} {body}")
        keys[label] = body["apiKey"]
    # Tenants may already hold runs (explicit keys on a reused app): expect baseline + what we add.
    baseline = {}
    for label in ("A", "B"):
        status, body = http("GET", f"{base}/api/v1/executions/analytics?flow={flow}", keys[label])
        baseline[label] = 0
        if status == 200 and isinstance(body, dict):
            baseline[label] = sum(f["executions"] for f in body.get("flows", []) if f["flowName"] == flow)
    counts = {"A": per_tenant, "B": max(3, per_tenant // 2)}
    for label in ("A", "B"):
        for i in range(counts[label]):
            status, body = run_flow(base, keys[label], flow, flow_payload(flow, i))
            if not (isinstance(body, dict) and body.get("executionId")):
                raise SystemExit(f"tenant {label} run #{i}: {status} {body}")
    # a waiting + resumed flow for tenant A only
    if flow == "SubmitExpense":
        seed_expense_flow(base, keys["A"], 6, 300, leave_waiting=1)
    else:
        seed_human(base, keys["A"], 4, 150, answer_key=keys["A"])

    checks = Verdict()
    results: dict[str, dict] = {}
    for label in ("A", "B"):
        status, body = http("GET", f"{base}/api/v1/executions/analytics", keys[label])
        checks.eq(f"tenant {label} own analytics HTTP", status, 200)
        results[label] = body if isinstance(body, dict) else {}
        checks.eq(f"tenant {label} analytics tenant", results[label].get("tenant"), tenants[label])
    a_flows = {f["flowName"]: f["executions"] for f in results["A"].get("flows", [])}
    b_flows = {f["flowName"]: f["executions"] for f in results["B"].get("flows", [])}
    extra_a = 6 if flow == "SubmitExpense" else 0  # the human-wait leg runs in tenant A's flow too
    checks.eq("A sees exactly its own order runs", a_flows.get(flow), baseline["A"] + counts["A"] + extra_a)
    checks.eq("B sees exactly its own order runs (not A's)", b_flows.get(flow), baseline["B"] + counts["B"])
    checks.true("B does not see tenant A's human-task flow",
                "AwaitCatalogEntryApproval" not in b_flows and (flow != "SubmitExpense" or b_flows.get(flow) == baseline["B"] + counts["B"]))
    checks.true("A does see a recorded human wait",
                (results["A"].get("humanWait", {}).get("resumedWaits", {}).get("count") or 0) > 0)
    if not baseline["B"]:
        checks.eq("B has no recorded human wait", (results["B"].get("humanWait", {}).get("resumedWaits", {}).get("count") or 0), 0)

    # B passing tenant=A must be refused outright, never answered with A's (or B's) data.
    status, body = http("GET", f"{base}/api/v1/executions/analytics?tenant={tenants['A']}", keys["B"])
    checks.eq("B asking for tenant=A -> HTTP", status, 403)
    status, body = http("GET", f"{base}/api/executions/analytics?tenant={tenants['A']}", keys["B"])
    checks.eq("B asking for tenant=A (legacy route) -> HTTP", status, 403)
    # B naming ITS OWN tenant explicitly is fine.
    status, body = http("GET", f"{base}/api/v1/executions/analytics?tenant={tenants['B']}", keys["B"])
    checks.eq("B naming its own tenant -> HTTP", status, 200)
    # Raw traces: B's list holds none of A's executions.
    a_ids = {t["meta"]["executionId"] for t in fetch_all_traces(base, keys["A"])}
    b_ids = {t["meta"]["executionId"] for t in fetch_all_traces(base, keys["B"])}
    checks.true("trace id sets are disjoint", a_ids.isdisjoint(b_ids) and a_ids and b_ids)

    # Each tenant's analytics must equal ground truth from ITS OWN traces.
    for label in ("A", "B"):
        verdict = verify(base, keys[label], None, quiet=True)
        checks.checks += verdict.checks
        checks.failures += [f"[{label}] {f}" for f in verdict.failures]

    if super_key or super_api_key:
        # --super-key: the real X-Super-User-Key credential (needs a physical-database app);
        # --super-api-key: a statically configured X-Api-Key whose principal has role SUPERUSER
        # (the only SUPERUSER an InMemory-engine app can have).
        for label in ("A", "B"):
            status, body = http("GET", f"{base}/api/v1/executions/analytics?tenant={tenants[label]}",
                                super_api_key, super_key=None if super_api_key else super_key)
            checks.eq(f"SUPERUSER tenant={label} -> HTTP", status, 200)
            if isinstance(body, dict):
                checks.eq(f"SUPERUSER tenant={label} analytics tenant", body.get("tenant"), tenants[label])
                flows = {f["flowName"]: f["executions"] for f in body.get("flows", [])}
                checks.eq(f"SUPERUSER sees tenant {label}'s order runs", flows.get(flow),
                          baseline[label] + counts[label] + (extra_a if label == "A" else 0))
    else:
        print("  (no --super-key: SUPERUSER leg skipped)")
    print(f"tenancy probe: {checks.checks} checks, {len(checks.failures)} failures")
    for failure in checks.failures:
        print("  FAIL " + failure)
    return 1 if checks.failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("seed", "seed-expense", "verify", "tenancy"):
        p = sub.add_parser(name)
        p.add_argument("--base-url", required=True)
        p.add_argument("--api-key", required=True)
        p.add_argument("--flow", default="PlaceWidgetOrder")
        if name == "seed":
            p.add_argument("--count", type=int, default=200)
            p.add_argument("--human", type=int, default=12, help="human-task executions (0 to skip)")
            p.add_argument("--human-wait-ms", type=int, default=600)
            p.add_argument("--approver-key", default=None, help="a key whose principal may answer the human task")
            p.add_argument("--quick-flow", default="RecordStripePayment", help="a second, cheap flow (empty to skip)")
            p.add_argument("--quick-count", type=int, default=60)
        if name == "seed-expense":
            p.add_argument("--count", type=int, default=200)
            p.add_argument("--human-wait-ms", type=int, default=1500)
        if name == "verify":
            p.add_argument("--filter-flow", default=None)
            p.add_argument("--tenant", default=None)
        if name == "tenancy":
            p.add_argument("--super-key", default=None)
            p.add_argument("--super-api-key", default=None)
            p.add_argument("--per-tenant", type=int, default=20)
            p.add_argument("--tenant-a", default=None)
            p.add_argument("--key-a", default=None)
            p.add_argument("--tenant-b", default=None)
            p.add_argument("--key-b", default=None)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    if args.command == "seed":
        seed(base, args.api_key, args.flow, args.count, args.human, args.human_wait_ms, args.approver_key,
             args.quick_flow, args.quick_count)
        return 0
    if args.command == "seed-expense":
        seed_expense_flow(base, args.api_key, args.count, args.human_wait_ms)
        return 0
    if args.command == "verify":
        verdict = verify(base, args.api_key, args.filter_flow, args.tenant)
        return 1 if verdict.failures else 0
    explicit = None
    if args.key_a and args.key_b and args.tenant_a and args.tenant_b:
        explicit = {"A": (args.tenant_a, args.key_a), "B": (args.tenant_b, args.key_b)}
    return tenancy(base, args.api_key, args.super_key, args.flow, args.per_tenant, explicit, args.super_api_key)


if __name__ == "__main__":
    sys.exit(main())
