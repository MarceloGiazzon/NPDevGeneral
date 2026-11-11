#!/usr/bin/env python3
"""REST acceptance driver for the ride-hailing sample (S2). Stdlib only.

Drives a RUNNING sample app (jwt auth, wiped database) through every objective of the plan and prints
one PASS/FAIL line per check, then a summary. Exit 0 only when every selected check passes.

  python -I drive_s2.py --base http://localhost:8150 --stages setup,roles,offer
  python -I drive_s2.py --base http://localhost:8150 --stages all --psp http://127.0.0.1:8701 --push http://127.0.0.1:8702

Stages (each assumes the earlier ones ran in the SAME invocation -- `setup` is always run first):
  setup    bootstrap admin, create users rider1/rider2/driver1/driver2/driver3/disp1, their Rider/Driver rows, two zones
  roles    objective 3: who may read/write what (positive AND negative checks)
  offer    objective 5: offer -> accept; offer -> decline -> next driver; offer -> timeout -> next driver; all ignore -> NO_DRIVER
  pay      objective 1: tok_ok, tok_async (webhook), tok_decline, tok_flaky (retry), tok_nowebhook (timeout -> NEEDS_REVIEW)
  price    objective 2: zone multiplier, peak, demand surge, cap
  rate     objective 6a: ratings + averages
  cancel   objective 6b: cancellation flags + rates
  notify   objective 6c: in-app notifications (+ push gateway when --push is given)
  route    objective 4: road distance via the OSRM mock, fallback to estimate

Endpoints are the platform's (verified 2026-11-11 from the source; re-check in SPIKE S-01 if a call 404s):
  POST /api/auth/bootstrap-admin, POST /api/auth/login, POST /api/auth/create-user (roleNames[])
  /api/concepts/<collection> (POST create, GET list, GET /{id}, PUT /{id})   collection = snake plural:
      riders, drivers, rides, ride_offers, payments, zones
  POST /api/v1/flows/<FlowName>/execute   body = the flow input
  GET /api/tasks, POST /api/tasks/<executionId>/complete {stepName, outcome, values}
  GET /api/notifications
Override any of them with --endpoints <json> (same keys as DEFAULT_ENDPOINTS).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

DEFAULT_ENDPOINTS = {
    "bootstrap": "/api/auth/bootstrap-admin",
    "login": "/api/auth/login",
    "createUser": "/api/auth/create-user",
    "collection": "/api/concepts/{collection}",
    "item": "/api/concepts/{collection}/{id}",
    "flow": "/api/v1/flows/{flow}/execute",
    "tasks": "/api/tasks",
    "taskComplete": "/api/tasks/{executionId}/complete",
    "notifications": "/api/notifications",
}
PASSWORD = "Ride-hailing-S2-pass-1"
OFFER_WINDOW_S = 20
TICK_SLACK_S = 8


class Run:
    def __init__(self, args):
        self.base = args.base.rstrip("/")
        self.ep = dict(DEFAULT_ENDPOINTS)
        if args.endpoints:
            self.ep.update(json.load(open(args.endpoints, encoding="utf-8")))
        self.psp = args.psp.rstrip("/") if args.psp else None
        self.push = args.push.rstrip("/") if args.push else None
        self.results: list[tuple[str, bool, str]] = []
        self.tokens: dict[str, str] = {}
        self.ids: dict[str, str] = {}
        self.seen_rides: set[str] = set()

    # ---- http ---------------------------------------------------------------------------------
    def call(self, method, path, body=None, who=None, base=None):
        url = (base or self.base) + path
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if who:
            headers["Authorization"] = "Bearer " + self.tokens[who]
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                return e.code, {"raw": raw.decode("utf-8", "replace")[:500]}

    def check(self, name, ok, detail=""):
        limit = int(os.environ.get("DRIVE_DETAIL_CHARS", "400"))
        self.results.append((name, bool(ok), "" if ok else str(detail)[:limit]))
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -- {str(detail)[:limit]}"), flush=True)
        return ok

    # ---- helpers ------------------------------------------------------------------------------
    def coll(self, c):
        return self.ep["collection"].format(collection=c)

    def item(self, c, i):
        return self.ep["item"].format(collection=c, id=i)

    def create(self, c, data, who="disp1"):
        return self.call("POST", self.coll(c), data, who)

    def get(self, c, i, who="disp1"):
        return self.call("GET", self.item(c, i), None, who)

    def update(self, c, i, changes, who="disp1"):
        """GET then PUT the merged record (the generated controller's PUT replaces the record)."""
        s, rec = self.get(c, i, who)
        if s != 200:
            return s, rec
        rec = dict(rec.get("data", rec))
        rec.update(changes)
        if who != "admin" and c == "rides":
            # System-set price/match fields are field-scoped: a PUT that names them is denied for a
            # driver even when unchanged, so leave them out (the gateway keeps the stored values).
            for f in ("demandMultiplier", "surgeMultiplier", "peakMultiplier", "pickupDistanceKm", "zoneMultiplier"):
                rec.pop(f, None)
        return self.call("PUT", self.item(c, i), rec, who)

    def flow(self, name, payload, who):
        return self.call("POST", self.ep["flow"].format(flow=name), payload, who)

    @staticmethod
    def rid(body):
        if not isinstance(body, dict):
            return None
        for k in ("id",):
            if body.get(k):
                return body[k]
        for k in ("data", "record", "output", "result"):
            if isinstance(body.get(k), dict):
                found = Run.rid(body[k])
                if found:
                    return found
        return None

    def ride(self, ride_id):
        s, body = self.get("rides", ride_id)
        return body.get("data", body) if s == 200 else {}

    def wait_ride(self, ride_id, pred, seconds):
        end = time.time() + seconds
        r = {}
        while time.time() < end:
            r = self.ride(ride_id)
            if pred(r):
                return r
            time.sleep(1)
        return r

    def tasks(self, who):
        s, body = self.call("GET", self.ep["tasks"], None, who)
        return body.get("items", []) if s == 200 else []

    def wait_task(self, who, seconds=10):
        end = time.time() + seconds
        while time.time() < end:
            items = self.tasks(who)
            if items:
                return items[0]
            time.sleep(1)
        return None

    def answer(self, who, task, outcome):
        return self.call("POST", self.ep["taskComplete"].format(executionId=task["executionId"]),
                         {"stepName": task["stepName"], "outcome": outcome, "values": {}}, who)

    def set_driver(self, key, lat, lng, status="AVAILABLE"):
        return self.update("drivers", self.ids[key], {"lat": lat, "lng": lng, "status": status})

    def request_ride(self, rider, plat=-23.5505, plng=-46.6339, dlat=-23.5614, dlng=-46.6544, run_async=True):
        """Starts RequestRide. The flow parks on the driver offer, so the HTTP call returns with the
        execution WAITING; we then find the ride by listing the rider's rides (newest)."""
        payload = {"rider": self.ids[rider], "riderAccount": rider, "status": "REQUESTED",
                   "pickupLat": plat, "pickupLng": plng, "dropoffLat": dlat, "dropoffLng": dlng}
        s, body = self.flow("RequestRide", payload, rider)
        rid = self.rid(body)
        if not rid:
            s2, lst = self.call("GET", self.coll("rides"), None, rider)
            if isinstance(lst, dict):
                items = lst.get("content") or lst.get("items") or []
            else:
                items = lst if isinstance(lst, list) else []
            mine = [i.get("data", i) for i in items if (i.get("data", i)).get("riderAccount") == rider]
            # The list is ordered by id (not by age): the new ride is the one this run has not seen yet.
            fresh = [i for i in mine if i["id"] not in self.seen_rides]
            rid = fresh[-1]["id"] if fresh else None
        if rid:
            self.seen_rides.add(rid)
        return s, body, rid


# ---- stages ---------------------------------------------------------------------------------------
def stage_setup(r: Run):
    s, b = r.call("POST", r.ep["bootstrap"], {"username": "admin", "displayName": "Admin", "password": PASSWORD,
                                               "email": "admin@example.test"})
    r.check("setup: bootstrap admin (201, or 409 when the DB was not wiped)", s in (201, 409), f"{s} {b}")
    s, b = r.call("POST", r.ep["login"], {"username": "admin", "password": PASSWORD})
    if not r.check("setup: admin signs in", s == 200 and b.get("token"), f"{s} {b}"):
        raise SystemExit("cannot continue without an admin token")
    r.tokens["admin"] = b["token"]
    users = {"rider1": "Rider", "rider2": "Rider", "driver1": "Driver", "driver2": "Driver", "driver3": "Driver",
             "disp1": "Dispatcher"}
    for u, role in users.items():
        s, b = r.call("POST", r.ep["createUser"], {"username": u, "displayName": u, "password": PASSWORD,
                                                    "email": f"{u}@example.test", "roleNames": [role]}, "admin")
        r.check(f"setup: create user {u} ({role})", s in (200, 201, 409), f"{s} {b}")
        s, b = r.call("POST", r.ep["login"], {"username": u, "password": PASSWORD})
        r.check(f"setup: {u} signs in", s == 200 and b.get("token"), f"{s} {b}")
        r.tokens[u] = b.get("token", "")
    for u in ("rider1", "rider2"):
        s, b = r.create("riders", {"name": u.title(), "account": u, "phone": "+55 11 90000-0000", "paymentToken": "tok_ok"})
        r.check(f"setup: Rider row {u}", s in (200, 201) and r.rid(b), f"{s} {b}")
        r.ids[u] = r.rid(b)
    spots = {"driver1": (-23.5520, -46.6350), "driver2": (-23.5600, -46.6400), "driver3": (-23.5700, -46.6500)}
    for i, (u, (lat, lng)) in enumerate(spots.items(), start=1):
        s, b = r.create("drivers", {"name": f"Driver {i}", "account": u, "plate": f"ABC-{1000 + i}{uuid.uuid4().hex[:2]}",
                                    "status": "OFFLINE", "lat": lat, "lng": lng})
        r.check(f"setup: Driver row {u}", s in (200, 201) and r.rid(b), f"{s} {b}")
        r.ids[u] = r.rid(b)
    s, b = r.create("zones", {"name": "Centro", "centerLat": -23.5505, "centerLng": -46.6339, "multiplier": 1.2, "peakMultiplier": 1.0, "peakValue": 1.3})
    r.ids["zoneCentro"] = r.rid(b)
    r.check("setup: zone Centro (x1.2) -- skip-safe before stage 40", s in (200, 201, 400, 404), f"{s} {b}")


def stage_roles(r: Run):
    # reads
    s, _ = r.get("riders", r.ids["rider1"], "rider1")
    r.check("roles: rider1 reads own Rider row", s == 200, s)
    s, _ = r.get("riders", r.ids["rider2"], "rider1")
    r.check("roles: rider1 can NOT read rider2's row (403/404)", s in (403, 404), s)
    s, _ = r.get("drivers", r.ids["driver1"], "rider1")
    r.check("roles: a rider can NOT read the Driver table", s in (403, 404), s)
    s, _ = r.get("drivers", r.ids["driver1"], "driver1")
    r.check("roles: driver1 reads own Driver row", s == 200, s)
    s, _ = r.get("drivers", r.ids["driver2"], "driver1")
    r.check("roles: driver1 can NOT read driver2", s in (403, 404), s)
    # writes
    s, _ = r.update("drivers", r.ids["driver1"], {"status": "AVAILABLE", "lat": -23.5520, "lng": -46.6350}, "driver1")
    r.check("roles: driver1 goes AVAILABLE and moves (own row)", s in (200, 204), s)
    s, _ = r.update("drivers", r.ids["driver1"], {"status": "BUSY"}, "driver1")
    r.check("roles: driver1 can NOT mark self BUSY (field rule)", s in (400, 403, 409, 422), s)
    s, _ = r.update("drivers", r.ids["driver1"], {"plate": "HACK-0001"}, "driver1")
    r.check("roles: driver1 can NOT change own plate (field rule)", s in (400, 403, 409, 422), s)
    s, _ = r.update("drivers", r.ids["driver2"], {"status": "AVAILABLE"}, "driver1")
    r.check("roles: driver1 can NOT touch driver2", s in (403, 404), s)
    s, _ = r.create("rides", {"rider": r.ids["rider2"], "riderAccount": "rider2", "status": "REQUESTED",
                              "pickupLat": -23.55, "pickupLng": -46.63, "dropoffLat": -23.56, "dropoffLng": -46.65}, "rider1")
    r.check("roles: rider1 can NOT create a ride in rider2's name", s in (400, 403, 409, 422), s)
    s, _ = r.create("zones", {"name": "Hack", "centerLat": 0, "centerLng": 0, "multiplier": 3}, "rider1")
    r.check("roles: a rider can NOT create zones", s in (403, 404), s)
    s, _ = r.get("riders", r.ids["rider2"], "disp1")
    r.check("roles: the Dispatcher reads any rider", s == 200, s)
    for d in ("driver1", "driver2", "driver3"):
        r.set_driver(d, *{"driver1": (-23.5520, -46.6350), "driver2": (-23.5600, -46.6400), "driver3": (-23.5700, -46.6500)}[d], "OFFLINE")


def stage_offer(r: Run):
    r.set_driver("driver1", -23.5520, -46.6350)
    r.set_driver("driver2", -23.5600, -46.6400)
    # 1. accept
    _, body, rid = r.request_ride("rider1")
    if not r.check("offer: RequestRide started and a ride exists", rid, body):
        return
    r.ids["rideA"] = rid
    t = r.wait_task("driver1")
    r.check("offer: the closest driver (driver1) gets the offer task", t,
            f"no task for driver1; flow answered {str(body)[:3000]}; disp1 sees {r.tasks('disp1')}")
    r.check("offer: driver2 sees NO task for it", not r.tasks("driver2"), r.tasks("driver2"))
    s, b = r.answer("driver2", t, "ACCEPT") if t else (0, {})
    r.check("offer: driver2 can NOT answer driver1's task (403)", s == 403, f"{s} {b}")
    s, b = r.answer("driver1", t, "ACCEPT") if t else (0, {})
    r.check("offer: driver1 accepts", s in (200, 201), f"{s} {b}")
    ride = r.wait_ride(rid, lambda x: x.get("status") == "ACCEPTED", 10)
    r.check("offer: ride ACCEPTED with driver1's login and plate on it",
            ride.get("status") == "ACCEPTED" and ride.get("driverAccount") == "driver1" and ride.get("driverPlate"), ride)
    s, rider_view = r.get("rides", rid, "rider1")
    r.check("offer: the rider sees the ride (and the driver's name), not the Driver row", s == 200, s)
    # 2. decline -> next driver
    r.set_driver("driver3", -23.5700, -46.6500)
    _, _, rid2 = r.request_ride("rider2")
    t = r.wait_task("driver2")
    r.check("offer: next free driver (driver2) gets ride 2", t, "no task")
    if t:
        r.answer("driver2", t, "DECLINE")
    t3 = r.wait_task("driver3", 10)
    r.check("offer: after DECLINE the ride is offered to driver3 (never driver2 again)", t3, "no task for driver3")
    # 3. timeout -> next / NO_DRIVER
    started = time.time()
    ride = r.wait_ride(rid2, lambda x: x.get("status") in ("NO_DRIVER", "ACCEPTED"), OFFER_WINDOW_S + TICK_SLACK_S + 5)
    took = time.time() - started
    r.check("offer: driver3 ignores it -> after ~20 s the offer EXPIRES and, no driver left, ride is NO_DRIVER",
            ride.get("status") == "NO_DRIVER", f"{ride.get('status')} after {took:.1f}s")
    r.check("offer: the timeout took 18-35 s (window 20 s + 2 s ticks)", 18 <= took <= 35 or ride.get("status") != "NO_DRIVER", f"{took:.1f}s")
    s, d2 = r.get("drivers", r.ids["driver2"])
    r.check("offer: drivers are AVAILABLE again after decline/timeout", (d2.get("data", d2)).get("status") == "AVAILABLE", d2)


def stage_pay(r: Run):
    if not r.psp:
        r.check("pay: --psp given (mock PSP running)", False, "start mocks\\mock_psp.py and pass --psp")
        return
    r.call("POST", "/mock/reset", {}, base=r.psp)
    rid = r.ids.get("rideA")
    if not rid:
        r.check("pay: needs ride A from stage offer", False, "run --stages setup,offer,pay")
        return
    s, b = r.update("rides", rid, {"status": "IN_PROGRESS"}, "driver1")
    r.check("pay: driver1 starts the trip (ACCEPTED -> IN_PROGRESS)", s in (200, 204), f"{s} {b}")
    s, _ = r.update("rides", rid, {"status": "COMPLETED"}, "rider1")
    r.check("pay: the RIDER can NOT complete the trip (guard)", s in (400, 403, 409, 422), s)

    def complete_with(token: str, ride_id: str):
        r.update("riders", r.ids["rider1"], {"paymentToken": token})
        return r.flow("CompleteRide", {"id": ride_id, "status": "COMPLETED"}, "driver1")

    s, b = complete_with("tok_ok", rid)
    r.check("pay: CompleteRide by driver1 runs", s in (200, 201, 202), f"{s} {b}")
    ride = r.wait_ride(rid, lambda x: x.get("paymentId"), 15)
    pid = ride.get("paymentId")
    s, p = r.get("payments", pid) if pid else (0, {})
    p = p.get("data", p)
    r.check("pay: tok_ok -> Payment CAPTURED, amount = fare", p.get("status") == "CAPTURED" and float(p.get("amountCents") or 0) > 0, p)
    s, lg = r.call("GET", "/mock/log", base=r.psp)
    keys = [i.get("idempotencyKey") for i in lg.get("items", []) if i.get("path") == "/v1/charges"]
    r.check("pay: the charge carried Idempotency-Key charge-<paymentId>", keys and keys[0] == f"charge-{pid}", keys)
    s, mine = r.get("payments", pid, "rider1")
    r.check("pay: the rider reads own payment", s == 200, s)
    s, _ = r.get("payments", pid, "rider2")
    r.check("pay: another rider can NOT read it", s in (403, 404), s)
    print("NOTE pay: the remaining payment scenarios (tok_async, tok_decline, tok_flaky, tok_nowebhook) each need a fresh"
          " COMPLETED-able ride; see ACCEPTANCE.md A1.4-A1.8 for the sequence. They are driven by stage 'paymore'.")


def stage_paymore(r: Run):
    """One fresh ride per scenario: request -> driver1 accepts -> start -> complete with a token."""
    expect = {"tok_async": ("CAPTURED", 25), "tok_decline": ("FAILED", 15), "tok_flaky": ("CAPTURED", 40),
              "tok_nowebhook": ("NEEDS_REVIEW", 150)}
    for token, (want, secs) in expect.items():
        r.set_driver("driver1", -23.5520, -46.6350)
        _, _, rid = r.request_ride("rider1")
        t = r.wait_task("driver1")
        if not t:
            r.check(f"paymore[{token}]: driver1 got the offer", False, "no task")
            continue
        r.answer("driver1", t, "ACCEPT")
        r.wait_ride(rid, lambda x: x.get("status") == "ACCEPTED", 10)
        r.update("rides", rid, {"status": "IN_PROGRESS"}, "driver1")
        r.update("riders", r.ids["rider1"], {"paymentToken": token})
        r.flow("CompleteRide", {"id": rid, "status": "COMPLETED"}, "driver1")
        end, p = time.time() + secs, {}
        while time.time() < end:
            pid = r.ride(rid).get("paymentId")
            if pid:
                p = r.get("payments", pid)[1]
                p = p.get("data", p)
                if p.get("status") == want:
                    break
            time.sleep(2)
        r.check(f"paymore[{token}]: Payment ends {want}", p.get("status") == want, p)


def stage_price(r: Run):
    for d in ("driver1", "driver2", "driver3"):
        r.set_driver(d, -23.5510, -46.6345)
    _, _, rid = r.request_ride("rider2")
    ride = r.wait_ride(rid, lambda x: x.get("surgeMultiplier") is not None, 10)
    r.check("price: zone Centro found, zoneMultiplier 1.2", str(ride.get("zoneName")) == "Centro" and float(ride.get("zoneMultiplier") or 0) == 1.2, ride)
    r.check("price: 1 open request vs 3 free drivers -> demand 1.0", float(ride.get("demandMultiplier") or 0) == 1.0, ride)
    r.check("price: fare = (300 + 120 km + 25 min) x surge",
            abs(float(ride.get("fareCents") or 0) - (300 + 120 * float(ride.get("tripDistanceKm") or 0) + 25 * float(ride.get("tripMinutes") or 0)) * float(ride.get("surgeMultiplier") or 1)) < 1, ride)
    for d in ("driver1", "driver2", "driver3"):
        r.set_driver(d, -23.5510, -46.6345, "OFFLINE")
    r.set_driver("driver1", -23.5510, -46.6345)
    rides = [r.request_ride(rider)[2] for rider in ("rider1", "rider2", "rider1")]
    last = r.wait_ride(rides[-1], lambda x: x.get("demandMultiplier") is not None, 10)
    r.check("price: 4 open requests vs 1 free driver -> demand 2.5 (capped)", float(last.get("demandMultiplier") or 0) == 2.5, last)
    r.check("price: surge = 1.2 x 1.0 x 2.5 = 3.0 (cap 3.0)", float(last.get("surgeMultiplier") or 0) == 3.0, last)
    print("NOTE price: the peak multiplier is driven by cron (07-10 and 17-20 on weekdays). To test it now, run the"
          " SetPeak procedure as admin (ACCEPTANCE.md A2.3) and request again.")


def stage_rate(r: Run):
    rid = r.ids.get("rideA")
    s, _ = r.update("rides", rid, {"driverStars": 5}, "rider1")
    r.check("rate: rider1 rates driver1 5 stars on a COMPLETED ride", s in (200, 204), s)
    s, _ = r.update("rides", rid, {"driverStars": 1}, "driver1")
    r.check("rate: the driver can NOT rate himself", s in (400, 403, 409, 422), s)
    s, _ = r.update("rides", rid, {"riderStars": 4}, "driver1")
    r.check("rate: driver1 rates rider1 4 stars", s in (200, 204), s)
    s, _ = r.update("rides", rid, {"driverStars": 9}, "rider1")
    r.check("rate: 9 stars is refused (1..5)", s in (400, 409, 422), s)
    s, d = r.get("drivers", r.ids["driver1"])
    d = d.get("data", d)
    r.check("rate: driver1.ratingAvg = 5 (rollup; empty ratings ignored)", float(d.get("ratingAvg") or 0) == 5.0, d)


def stage_cancel(r: Run):
    r.set_driver("driver1", -23.5520, -46.6350)
    _, _, rid = r.request_ride("rider2")
    t = r.wait_task("driver1")
    if t:
        r.answer("driver1", t, "ACCEPT")
    r.wait_ride(rid, lambda x: x.get("status") == "ACCEPTED", 10)
    s, _ = r.flow("CancelRide", {"id": rid, "status": "CANCELLED", "cancelledBy": "DRIVER", "cancelledByDriverFlag": 1}, "rider2")
    r.check("cancel: rider2 can NOT claim the DRIVER cancelled", s >= 400, s)
    s, _ = r.flow("CancelRide", {"id": rid, "status": "CANCELLED", "cancelledBy": "RIDER", "cancelledByRiderFlag": 1}, "rider2")
    r.check("cancel: rider2 cancels own accepted ride", s in (200, 201, 202), s)
    s, d = r.get("drivers", r.ids["driver1"])
    r.check("cancel: driver1 is AVAILABLE again", (d.get("data", d)).get("status") == "AVAILABLE", d)
    s, rr = r.get("riders", r.ids["rider2"])
    rr = rr.get("data", rr)
    r.check("cancel: rider2.cancelsByRider >= 1 and cancelRate > 0", int(rr.get("cancelsByRider") or 0) >= 1 and float(rr.get("cancelRate") or 0) > 0, rr)


def stage_notify(r: Run):
    s, b = r.call("GET", r.ep["notifications"], None, "rider1")
    titles = [i.get("title", "") for i in b.get("items", [])] if s == 200 else []
    r.check("notify: rider1 got 'Your driver is on the way'", any("on the way" in t for t in titles), titles)
    s, b = r.call("GET", r.ep["notifications"], None, "driver1")
    titles = [i.get("title", "") for i in b.get("items", [])] if s == 200 else []
    r.check("notify: driver1 got 'New ride offer'", any("ride offer" in t for t in titles), titles)
    if r.push:
        s, b = r.call("GET", "/mock/sent?to=rider1", base=r.push)
        r.check("notify: the push gateway received rider1's notifications (task N1)", s == 200 and b.get("count", 0) > 0, b)


def stage_route(r: Run):
    for d in ("driver1",):
        r.set_driver(d, -23.5520, -46.6350)
    _, _, rid = r.request_ride("rider1")
    ride = r.wait_ride(rid, lambda x: x.get("distanceMethod"), 10)
    r.check("route: trip measured by road through the OSRM mock", ride.get("distanceMethod") == "road", ride)
    _, _, rid0 = r.request_ride("rider2", 0.0, 0.0, -23.56, -46.65)
    ride0 = r.wait_ride(rid0, lambda x: x.get("distanceMethod"), 10)
    r.check("route: no route -> falls back to 'estimate' (straight line x 1.3)", ride0.get("distanceMethod") == "estimate", ride0)


STAGES = {"setup": stage_setup, "roles": stage_roles, "offer": stage_offer, "pay": stage_pay, "paymore": stage_paymore,
          "price": stage_price, "rate": stage_rate, "cancel": stage_cancel, "notify": stage_notify, "route": stage_route}
ORDER = ["setup", "roles", "offer", "pay", "paymore", "price", "rate", "cancel", "notify", "route"]


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--base", default="http://localhost:8150")
    p.add_argument("--stages", default="setup,roles")
    p.add_argument("--psp")
    p.add_argument("--push")
    p.add_argument("--endpoints")
    a = p.parse_args(argv)
    wanted = ORDER if a.stages == "all" else ["setup"] + [s for s in a.stages.split(",") if s and s != "setup"]
    unknown = [s for s in wanted if s not in STAGES]
    if unknown:
        print("unknown stage(s):", unknown)
        return 2
    r = Run(a)
    for name in [s for s in ORDER if s in wanted]:
        print(f"== {name}")
        try:
            STAGES[name](r)
        except SystemExit:
            raise
        except Exception as e:  # a crash in one stage must not hide the others
            r.check(f"{name}: stage crashed", False, repr(e))
    failed = [n for n, ok, _ in r.results if not ok]
    print(f"\n{len(r.results) - len(failed)}/{len(r.results)} passed")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
