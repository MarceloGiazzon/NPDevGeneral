"""LOAD-3: deterministic data seeding for a load run, through the app's own REST API.

    python scripts/load/seed.py --target sales --volume 10000 --base http://127.0.0.1:8421 \
        --key mgr=<key> --key rep=<key>

Volume is the row count of the target's largest concept (workload.json `seed.ratio` sizes the rest).
Every value comes from random.Random(--seed), so two runs seed the same data and compare. Goes through
the API (so it works on any engine and exercises the same validation as a user) and writes
`pools-<target>.json` beside the load report: real ids that run_load.py substitutes into `{orders}`
style placeholders. Seeding is sized for 10k and 100k rows; a 1M-row seed needs a direct SQL load on
the target engine, which this script deliberately does not pretend to be.
"""
import argparse
import json
import random
import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_common import REPO, call, load_workload, parse_keys, rows_of  # noqa: E402

POOL_LIMIT = 2000


def sized(volume, ratio):
    return max(1, int(volume * ratio))


def run_parallel(work, items, workers):
    done, failed = 0, []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for outcome in pool.map(work, items):
            if outcome is None or outcome[0] not in (200, 201):
                failed.append(outcome)
            else:
                done += 1
    return done, failed


def post_all(base, key, path, bodies, workers):
    ids = [None] * len(bodies)

    def one(index):
        status, payload, _ = call(base, key, "POST", path, bodies[index])
        if status in (200, 201) and isinstance(payload, dict):
            ids[index] = payload.get("id")
        return status, payload

    done, failed = run_parallel(one, range(len(bodies)), workers)
    if failed:
        print(f"  {len(failed)} of {len(bodies)} POST {path} failed, first: {str(failed[0])[:240]}", file=sys.stderr)
    return [i for i in ids if i]


def seed_sales(base, keys, volume, ratio, rng, workers, run):
    mgr, rep = keys["mgr"], keys["rep"]
    counts = {name: sized(volume, r) for name, r in ratio.items()}
    started = time.time()
    customers = post_all(base, mgr, "/api/sales_customers",
                         [{"code": f"LC{run}{i}", "name": f"Customer {run}-{i:06d}", "creditLimit": rng.randint(1, 100) * 1000, "active": True}
                          for i in range(counts["customers"])], workers)
    items = post_all(base, mgr, "/api/sales_sales_items",
                     [{"sku": f"LI{run}{i}", "description": f"Item {i:05d}", "unitPrice": rng.randint(1, 500), "active": True}
                      for i in range(counts["items"])], workers)
    orders = post_all(base, rep, "/api/sales_sales_orders",
                      [{"orderNumber": f"LO{run}{i}", "customerId": rng.choice(customers)} for i in range(counts["orders"])], workers)
    # a deterministic third of the orders is confirmed so the filtered list returns rows
    confirm = [o for index, o in enumerate(orders) if index % 3 == 0]

    def confirm_one(order_id):
        status, payload, _ = call(base, rep, "PUT", f"/api/sales_sales_orders/{order_id}", {"status": "CONFIRMED"})
        return status, payload

    run_parallel(confirm_one, confirm, workers)
    lines = [{"orderId": rng.choice(orders), "itemId": rng.choice(items), "quantity": rng.randint(1, 20), "unitPrice": rng.randint(1, 500)}
             for _ in range(counts["lines"])]
    made = len(post_all(base, rep, "/api/sales_sales_order_lines", lines, workers))
    print(f"seeded customers={len(customers)} items={len(items)} orders={len(orders)} lines={made} in {time.time() - started:.0f}s")
    return {"customers": customers[:POOL_LIMIT], "items": items[:POOL_LIMIT], "orders": orders[:POOL_LIMIT]}


def seed_leave(base, keys, volume, ratio, rng, workers, run):
    hr = keys["hr"]
    types = post_all(base, hr, "/api/leave_leave_types",
                     [{"code": f"LT{run}{i}", "name": f"Type {run}-{i}", "paid": True, "annualAllowanceDays": 20, "active": True}
                      for i in range(sized(volume, ratio["leaveTypes"]))], workers)
    count = sized(volume, ratio["requests"])
    requests = post_all(base, hr, "/api/leave_leave_requests",
                        [{"requestNumber": f"LR{run}{i}", "employeeName": f"Employee {rng.randint(1, 500):04d}", "leaveTypeId": rng.choice(types),
                          "startDate": "2026-11-01", "endDate": "2026-11-02", "days": rng.randint(1, 5)} for i in range(count)], workers)
    print(f"seeded leaveTypes={len(types)} requests={len(requests)}")
    return {"leaveTypes": types[:POOL_LIMIT], "requests": requests[:POOL_LIMIT]}


SEEDERS = {"sales": seed_sales, "leave": seed_leave}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", required=True)
    ap.add_argument("--volume", type=int, required=True, help="rows in the target's largest concept")
    ap.add_argument("--base", help="override the workload's baseUrl")
    ap.add_argument("--key", action="append", default=[], help="identity=apiKey, repeat per identity")
    ap.add_argument("--seed", type=int, default=20261019)
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--out", help="directory for pools-<target>.json (default <Build root>/load)")
    args = ap.parse_args()

    _, target = load_workload(args.target)
    base = (args.base or target["baseUrl"]).rstrip("/")
    keys = parse_keys(args.key)
    missing = [name for name in target["identities"] if name not in keys]
    if missing:
        sys.exit(f"missing --key for identities: {missing}")
    if args.target not in SEEDERS:
        sys.exit(f"no seeder for target '{args.target}'")
    if call(base, next(iter(keys.values())), "GET", "/actuator/health")[0] != 200:
        sys.exit(f"{base} is not answering /actuator/health")

    rng = random.Random(args.seed)
    run = format(args.seed % 100000, "x")  # stable suffix, so re-seeding the same DB collides loudly instead of silently doubling
    pools = SEEDERS[args.target](base, keys, args.volume, target["seed"]["ratio"], rng, args.workers, run)
    out = Path(args.out) if args.out else REPO.parent / "Build" / "load"
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"pools-{args.target}.json"
    path.write_text(json.dumps({"target": args.target, "volume": args.volume, "seed": args.seed, "pools": pools}), encoding="utf-8")
    print(f"pools written to {path}")


if __name__ == "__main__":
    main()
