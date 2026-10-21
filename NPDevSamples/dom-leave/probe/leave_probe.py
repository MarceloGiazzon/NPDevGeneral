"""DOM-5: drives the leave pack through a booted consumer app (NPDevSamples/dom-leave).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<emp>=dev:emp:EMPLOYEE;<appr>=dev:appr:LEAVE_APPROVER;<hr>=dev:mgr:HR_ADMIN;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same five keys:

    python leave_probe.py http://127.0.0.1:8424 <admin> <emp> <appr> <hr> <plain>

Rows from earlier runs may exist; every code and count here is scoped to rows this run created.
Exit 0 = every check passed.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

if len(sys.argv) != 7:
    sys.exit(__doc__)
BASE = sys.argv[1]
KEYS = dict(zip(("admin", "emp", "appr", "hr", "plain"), sys.argv[2:7]))
R = uuid.uuid4().hex[:6]
results = []
P = "/api/leave_"


def call(who, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"X-Api-Key": KEYS[who], "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("  -> " + str(detail)[:260] if (detail and not ok) else ""))


def make(who, path, body, expect=201):
    status, resp = call(who, "POST", path, body)
    check(f"{who} POST {path} -> {expect}", status == expect, (status, resp))
    return resp if isinstance(resp, dict) else {}


def refused(name, who, path, body, codes=(422,)):
    s, r = call(who, "POST", path, body)
    check(name, s in codes, (s, r))


def rows(query, key, value):
    s, r = call("hr", "GET", f"/api/queries/leave::{query}?limit=1000")
    return [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if x.get(key) == value]




def listing(who, path):
    s, r = call(who, "GET", path + "?limit=1000")
    return s, (r.get("rows", []) if isinstance(r, dict) else (r or []))


def request(num):
    return next((x for x in listing("hr", P + "leave_requests")[1] if x.get("requestNumber") == num), {})


def file_leave(num, days=3, start="2026-12-01", end="2026-12-03", who="emp"):
    body = {"requestNumber": num, "employeeName": "Eva " + R, "leaveTypeId": LTID, "startDate": start, "endDate": end, "days": days, "reason": "trip"}
    s, r = call(who, "POST", "/api/flows/leave::RequestLeave/execute", body)
    ok = s == 202 and isinstance(r, dict) and r.get("status") == "WAITING_EVENT"
    check(f"{who} files {num} through the flow (waits for a decision)", ok, (s, r))
    return (r or {}).get("executionId") if isinstance(r, dict) else None


def my_tasks(who):
    s, r = call(who, "GET", "/api/tasks")
    return (r or {}).get("items", []) if isinstance(r, dict) else []


def decide(who, execution, outcome, ok=True, comment="fine"):
    s, r = call(who, "POST", f"/api/tasks/{execution}/complete", {"stepName": "await-decision", "outcome": outcome, "values": {"comment": comment}})
    check(f"{who} decides {outcome} ({'allowed' if ok else 'refused'})", (s in (200, 201)) if ok else (s in (403, 404, 409, 422)), (s, r))


def patch(who, rid, status, ok=True):
    s, r = call(who, "PATCH", f"{P}leave_requests/{rid}", {"status": status})
    if s == 405:
        s, r = call(who, "PUT", f"{P}leave_requests/{rid}", {"status": status})
    check(f"{who}: -> {status} ({'allowed' if ok else 'refused'})", (s in (200, 201)) if ok else (s in (403, 422)), (s, r))


lt = make("hr", P + "leave_types", {"code": "AN" + R, "name": "Annual " + R, "paid": True, "annualAllowanceDays": 20, "active": True})
LTID = lt.get("id")

# roles and invariants
refused("an employee cannot create a leave type", "emp", P + "leave_types", {"code": "X" + R, "name": "x", "active": True}, (401, 403))
refused("an approver cannot create a leave type", "appr", P + "leave_types", {"code": "Y" + R, "name": "y", "active": True}, (401, 403))
refused("a duplicate leave-type code is refused", "hr", P + "leave_types", {"code": "AN" + R, "name": "dup", "active": True}, (409, 422))
refused("a negative allowance is refused", "hr", P + "leave_types", {"code": "N" + R, "name": "n", "annualAllowanceDays": -1, "active": True})
s, fr = call("plain", "POST", "/api/flows/leave::RequestLeave/execute", {"requestNumber": "P" + R, "employeeName": "x", "leaveTypeId": LTID, "startDate": "2026-12-01", "endDate": "2026-12-01", "days": 1})
check("a user with no leave role cannot file a request (flow.execute denied)", s in (401, 403) or (isinstance(fr, dict) and fr.get("errorCode") == "forbidden"), (s, fr))

# invalid requests never reach an approver
before = len(my_tasks("appr"))
for name, kw in (("zero days", dict(days=0)), ("an end date before the start date", dict(start="2026-12-05", end="2026-12-01"))):
    s, r = call("emp", "POST", "/api/flows/leave::RequestLeave/execute", {"requestNumber": "B" + R + name[0], "employeeName": "Eva " + R, "leaveTypeId": LTID,
                "startDate": kw.get("start", "2026-12-01"), "endDate": kw.get("end", "2026-12-03"), "days": kw.get("days", 3)})
    check(f"a request with {name} is refused", s in (400, 422) or (isinstance(r, dict) and r.get("status") not in ("WAITING_EVENT", "COMPLETED")), (s, r))
check("refused requests leave no task", len(my_tasks("appr")) == before, (before, len(my_tasks("appr"))))

# approve / reject through My tasks
e1 = file_leave("LV1" + R, days=3)
e2 = file_leave("LV2" + R, days=2, start="2026-12-10", end="2026-12-11")
e3 = file_leave("LV3" + R, days=1, start="2026-12-15", end="2026-12-15")
check("a new request is PENDING", request("LV1" + R).get("status") == "PENDING", request("LV1" + R))
check("the approver sees the three tasks", {e1, e2, e3} <= {t.get("executionId") for t in my_tasks("appr")}, None)
check("the employee sees none of them", not ({e1, e2, e3} & {t.get("executionId") for t in my_tasks("emp")}), None)
decide("emp", e1, "APPROVED", ok=False)
check("a refused decision leaves the request PENDING", request("LV1" + R).get("status") == "PENDING", request("LV1" + R))
decide("appr", e1, "APPROVED", comment="enjoy")
decide("appr", e2, "APPROVED")
decide("appr", e3, "REJECTED", comment="busy week")
r1, r2, r3 = request("LV1" + R), request("LV2" + R), request("LV3" + R)
check("LV1 is APPROVED with the approver and comment recorded", r1.get("status") == "APPROVED" and r1.get("decidedBy") and r1.get("decisionComment") == "enjoy", r1)
check("LV3 is REJECTED with the reason recorded", r3.get("status") == "REJECTED" and r3.get("decisionComment") == "busy week", r3)
decide("appr", e1, "REJECTED", ok=False)   # already answered
check("an answered task is gone from My tasks", not ({e1, e2, e3} & {t.get("executionId") for t in my_tasks("appr")}), None)

# lifecycle
patch("hr", r3.get("id"), "CANCELLED", ok=False)   # REJECTED is final
patch("hr", r2.get("id"), "CANCELLED")             # an approved request may be cancelled
patch("hr", r2.get("id"), "APPROVED", ok=False)    # and cannot come back
patch("emp", r1.get("id"), "CANCELLED", ok=False)  # employees cannot update

# cancelling while pending: a later decision is ignored
e4 = file_leave("LV4" + R, days=4, start="2026-12-20", end="2026-12-23")
r4 = request("LV4" + R)
patch("hr", r4.get("id"), "CANCELLED")
decide("appr", e4, "APPROVED")
check("a decision on a cancelled request is ignored", request("LV4" + R).get("status") == "CANCELLED", request("LV4" + R))

# derived days: only LV1 (3 days) counts; LV2 was cancelled, LV3 rejected, LV4 cancelled
taken = rows("LeaveTakenByEmployee", "employeeName", "Eva " + R)
print("LeaveTakenByEmployee:", json.dumps(taken)[:200])
check("Eva has 3 approved days on 1 request", len(taken) == 1 and float(taken[0].get("daysApproved", -1)) == 3 and int(taken[0].get("requests", -1)) == 1, taken)
pend = [x for x in listing("hr", "/api/queries/leave::PendingLeave")[1] if str(x.get("requestNumber", "")).endswith(R)]
check("PendingLeave holds none of this run's requests", not pend, pend)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
