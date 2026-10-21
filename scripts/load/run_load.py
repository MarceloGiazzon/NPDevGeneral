"""LOAD-2: closed-loop load harness driven by NPDevSamples/load/workload.json (standard library only).

    python scripts/load/run_load.py --target sales --users 8 --seconds 60 --base http://127.0.0.1:8421 \
        --key rep=<key> --key mgr=<key>

Run seed.py first: it writes the id pools the `{orders}` / `{customers}` placeholders draw from. Each
virtual user loops: pick a scenario by weight, call it, record the latency, repeat until time is up.
A call counts as an error when its status is not 2xx (or not the scenario's `expectStatus`) or the
request failed outright. The report (JSON, `<Build root>/load/report-<target>-<utc>.json`) holds
throughput, error rate and p50/p95/p99 per scenario; thresholds come from the workload and the exit
code is 1 when one is broken. Never run it against a machine you are not allowed to hammer.
"""
import argparse
import json
import random
import sys
import threading
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_common import REPO, Counter, call, load_workload, parse_keys, percentile  # noqa: E402


def fill(value, pools, rng, unique):
    """Substitute {unique} and {pool} placeholders in a string, list or dict."""
    if isinstance(value, str):
        out = value.replace("{unique}", unique())
        for name, ids in pools.items():
            if "{" + name + "}" in out:
                out = out.replace("{" + name + "}", rng.choice(ids))
        return out
    if isinstance(value, list):
        return [fill(v, pools, rng, unique) for v in value]
    if isinstance(value, dict):
        return {k: fill(v, pools, rng, unique) for k, v in value.items()}
    return value


METHOD = {"list": "GET", "read": "GET", "create": "POST", "update": "PUT", "flow": "POST"}


def user_loop(base, target, keys, pools, deadline, samples, lock, seed, unique):
    rng = random.Random(seed)
    scenarios = target["scenarios"]
    weights = [s["weight"] for s in scenarios]
    while time.time() < deadline:
        scenario = rng.choices(scenarios, weights)[0]
        path = fill(scenario["path"], pools, rng, unique)
        body = fill(scenario.get("body"), pools, rng, unique) if scenario.get("body") else None
        status, _, elapsed = call(base, keys[scenario["as"]], METHOD[scenario["kind"]], path, body, scenario.get("query"))
        expected = scenario.get("expectStatus")
        ok = (status == expected) if expected else (200 <= status < 300)
        with lock:
            samples[scenario["name"]].append((elapsed, ok, status))


def summarise(samples, seconds):
    report, total, errors = {}, 0, 0
    for name, rows in samples.items():
        times = sorted(r[0] for r in rows)
        bad = [r for r in rows if not r[1]]
        total, errors = total + len(rows), errors + len(bad)
        report[name] = {
            "calls": len(rows), "errors": len(bad), "errorRate": round(len(bad) / len(rows), 4),
            "p50Ms": round(percentile(times, 0.50), 1), "p95Ms": round(percentile(times, 0.95), 1), "p99Ms": round(percentile(times, 0.99), 1),
            "maxMs": round(times[-1], 1), "statuses": sorted({r[2] for r in bad}),
        }
    return report, {"calls": total, "errors": errors, "errorRate": round(errors / total, 4) if total else 1.0, "callsPerSecond": round(total / seconds, 1)}


def check_thresholds(thresholds, per_scenario, overall):
    broken = []
    if overall["errorRate"] > thresholds["errorRate"]:
        broken.append(f"overall error rate {overall['errorRate']} > {thresholds['errorRate']}")
    limits = thresholds["p95Ms"]
    for name, stats in per_scenario.items():
        limit = limits.get(name, limits["default"])
        if stats["p95Ms"] > limit:
            broken.append(f"{name}: p95 {stats['p95Ms']} ms > {limit} ms")
    return broken


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True)
    ap.add_argument("--users", type=int, default=8)
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--base", help="override the workload's baseUrl")
    ap.add_argument("--key", action="append", default=[], help="identity=apiKey, repeat per identity")
    ap.add_argument("--seed", type=int, default=20261019)
    ap.add_argument("--out", help="directory holding pools-<target>.json and receiving the report (default <Build root>/load)")
    args = ap.parse_args()

    _, target = load_workload(args.target)
    base = (args.base or target["baseUrl"]).rstrip("/")
    keys = parse_keys(args.key)
    missing = [name for name in target["identities"] if name not in keys]
    if missing:
        sys.exit(f"missing --key for identities: {missing}")
    out = Path(args.out) if args.out else REPO.parent / "Build" / "load"
    pools_file = out / f"pools-{args.target}.json"
    if not pools_file.exists():
        sys.exit(f"{pools_file} not found: run scripts/load/seed.py first")
    pools_doc = json.loads(pools_file.read_text(encoding="utf-8"))
    pools = {name: ids for name, ids in pools_doc["pools"].items() if ids}

    samples, lock = defaultdict(list), threading.Lock()
    unique = Counter(f"L{int(time.time()) % 100000:x}")
    deadline = time.time() + args.seconds
    threads = [threading.Thread(target=user_loop, args=(base, target, keys, pools, deadline, samples, lock, args.seed + n, unique)) for n in range(args.users)]
    started = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    seconds = time.time() - started

    per_scenario, overall = summarise(samples, seconds)
    broken = check_thresholds(target["thresholds"], per_scenario, overall)
    report = {"target": args.target, "volume": pools_doc["volume"], "users": args.users, "seconds": round(seconds, 1), "baseUrl": base,
              "overall": overall, "scenarios": per_scenario, "thresholdsBroken": broken, "at": datetime.now(timezone.utc).isoformat()}
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"report-{args.target}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(f"{args.target} @ {pools_doc['volume']} rows, {args.users} users, {seconds:.0f}s: {overall['calls']} calls, "
          f"{overall['callsPerSecond']}/s, error rate {overall['errorRate']}")
    print(f"{'scenario':<26}{'calls':>8}{'err':>6}{'p50':>9}{'p95':>9}{'p99':>9}")
    for name, s in per_scenario.items():
        print(f"{name:<26}{s['calls']:>8}{s['errors']:>6}{s['p50Ms']:>9}{s['p95Ms']:>9}{s['p99Ms']:>9}")
    for line in broken:
        print("THRESHOLD BROKEN:", line)
    print("report:", path)
    sys.exit(1 if broken else 0)


if __name__ == "__main__":
    main()
