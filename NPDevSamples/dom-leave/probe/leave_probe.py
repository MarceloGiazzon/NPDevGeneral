"""DOM-5: drives the leave pack through a booted consumer app (NPDevSamples/dom-leave).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<emp>=dev:emp:EMPLOYEE;<emp2>=dev:emp2:EMPLOYEE;<appr>=dev:appr:LEAVE_APPROVER;<hr>=dev:mgr:HR_ADMIN;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same six keys (two different employees, so "an employee sees only their own
requests" can be proved):

    python leave_probe.py http://127.0.0.1:8424 <admin> <emp> <emp2> <appr> <hr> <plain>

Rows from earlier runs may exist; every code and count here is scoped to rows this run created.
Exit 0 = every check passed.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

if len(sys.argv) != 8:
    sys.exit(__doc__)
BASE = sys.argv[1]
KEYS = dict(zip(("admin", "emp", "emp2", "appr", "hr", "plain"), sys.argv[2:8]))
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


def file_leave(num, days=3, start="2026-12-01", end="2026-12-03", who="emp", name=None):
    body = {"requestNumber": num, "employeeName": name or "Eva " + R, "leaveTypeId": LTID, "startDate": start, "endDate": end, "days": days, "reason": "trip"}
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

# ---- an employee sees ONLY their own requests; approvers, HR and ADMIN see all (needs two employee identities)
ANN, BEN = "Ann " + R, "Ben " + R


def mine(who):
    """This run's requests as `who` sees them in the LeaveRequests list: {requestNumber: row}."""
    s, rws = listing(who, P + "leave_requests")
    return {x.get("requestNumber"): x for x in rws if str(x.get("requestNumber", "")).endswith(R)}


def by_id(who, rid):
    s, r = call(who, "GET", f"{P}leave_requests/{rid}")
    return s, r


def quiet_cancel(num):
    row = request(num)
    if row:
        call("hr", "PATCH", f"{P}leave_requests/{row.get('id')}", {"status": "CANCELLED"})


A, B, C, D = "OA" + R, "OB" + R, "OC" + R, "OD" + R
eA = file_leave(A, days=2, start="2027-01-11", end="2027-01-12", who="emp", name=ANN)
eB = file_leave(B, days=2, start="2027-01-11", end="2027-01-12", who="emp2", name=BEN)
rA, rB = request(A), request(B)
LOGIN1, LOGIN2 = rA.get("employeeId"), rB.get("employeeId")
print("employeeId stored for emp / emp2:", LOGIN1, "/", LOGIN2)
check("employeeId is filled with the filing login on both requests (and differs)", bool(LOGIN1) and bool(LOGIN2) and LOGIN1 != LOGIN2, (rA, rB))
check("the same login is stored on every request emp filed (employeeId == $user.id)", LOGIN1 == request("LV1" + R).get("employeeId") == request("LV4" + R).get("employeeId"), (LOGIN1, request("LV1" + R)))
check("both new requests are PENDING", rA.get("status") == "PENDING" and rB.get("status") == "PENDING", (rA, rB))

# the employee's list
seen1, seen2 = mine("emp"), mine("emp2")
check("emp's list holds A (their own)", A in seen1, sorted(seen1))
check("emp's list does not hold B (emp2's)", B not in seen1, sorted(seen1))
check("emp2's list holds B (their own)", B in seen2, sorted(seen2))
check("emp2's list does not hold A (emp's)", A not in seen2, sorted(seen2))
check("emp sees their old requests too (LV1..LV4) and none of emp2's", {"LV1" + R, "LV2" + R, "LV3" + R, "LV4" + R, A} <= set(seen1) and B not in seen1, sorted(seen1))
s, got = by_id("emp", rA.get("id"))
check("emp GET A by id -> 200", s == 200 and isinstance(got, dict) and got.get("requestNumber") == A, (s, got))
s, got = by_id("emp", rB.get("id"))
check("emp GET B (emp2's) by id is refused or not found", s in (403, 404) and not (isinstance(got, dict) and got.get("requestNumber") == B), (s, got))
s, got = by_id("emp2", rB.get("id"))
check("emp2 GET B by id -> 200", s == 200 and isinstance(got, dict) and got.get("requestNumber") == B, (s, got))
s, got = by_id("emp2", rA.get("id"))
check("emp2 GET A (emp's) by id is refused or not found", s in (403, 404) and not (isinstance(got, dict) and got.get("requestNumber") == A), (s, got))

# staff see everything
for who in ("appr", "hr", "admin"):
    st, seen = listing(who, P + "leave_requests")
    nums = {x.get("requestNumber") for x in seen}
    check(f"{who} sees both A and B in the list", st == 200 and {A, B} <= nums, (st, len(seen)))
    s, got = by_id(who, rB.get("id"))
    check(f"{who} GET B by id -> 200", s == 200 and isinstance(got, dict) and got.get("requestNumber") == B, (s, got))
s, lst = call("emp", "GET", P + "leave_requests?limit=1000")
if isinstance(lst, dict):
    shown = {k: v for k, v in lst.items() if k != "rows" and not isinstance(v, (list, dict))}
    print("INFO emp list envelope (rows =", len(lst.get("rows", [])), "):", json.dumps(shown))

# derived queries and the LeaveRequests collection, as an employee sees them while A and B are both PENDING
for who in ("emp", "emp2"):
    s, r = call(who, "GET", "/api/queries/leave::PendingLeave?limit=1000")
    qrows = [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if str(x.get("requestNumber", "")).endswith(R)]
    nums = {x.get("requestNumber") for x in qrows}
    other = B if who == "emp" else A
    own = A if who == "emp" else B
    print(f"INFO PendingLeave as {who}: HTTP {s}, own request {'visible' if own in nums else 'not visible'}, "
          f"the other employee's {'LEAKED (visible)' if other in nums else 'not visible'}, this run's rows: {sorted(nums)}")

# approve A and B from My tasks; the owner still sees their own, only
decide("appr", eA, "APPROVED", comment="enjoy")
decide("appr", eB, "APPROVED", comment="go")
seen1, seen2 = mine("emp"), mine("emp2")
check("after approval emp still sees A, now APPROVED, with the decision recorded", seen1.get(A, {}).get("status") == "APPROVED" and seen1.get(A, {}).get("decisionComment") == "enjoy", seen1.get(A))
check("after approval emp still does not see B", B not in seen1, sorted(seen1))
check("after approval emp2 still sees B, now APPROVED", seen2.get(B, {}).get("status") == "APPROVED", seen2.get(B))
check("after approval emp2 still does not see A", A not in seen2, sorted(seen2))
s, got = by_id("emp", rA.get("id"))
check("emp GET A by id shows APPROVED", s == 200 and isinstance(got, dict) and got.get("status") == "APPROVED", (s, got))

# rejection and cancellation still work, each seen by its owner only
eD = file_leave(D, days=1, start="2027-02-01", end="2027-02-01", who="emp2", name=BEN)
decide("appr", eD, "REJECTED", comment="no cover")
seen1, seen2 = mine("emp"), mine("emp2")
check("a rejected request shows REJECTED to its owner (emp2) and stays hidden from emp", seen2.get(D, {}).get("status") == "REJECTED" and D not in seen1, (seen2.get(D), sorted(seen1)))
eC = file_leave(C, days=1, start="2027-02-08", end="2027-02-08", who="emp", name=ANN)
patch("hr", request(C).get("id"), "CANCELLED")
decide("appr", eC, "APPROVED")   # the late decision is ignored
seen1, seen2 = mine("emp"), mine("emp2")
check("a cancelled request shows CANCELLED to its owner (emp), and the late decision was ignored", seen1.get(C, {}).get("status") == "CANCELLED" and C not in seen2, (seen1.get(C), sorted(seen2)))

# creating a request directly (not through the flow) as an employee
E = "OE" + R
direct = {"requestNumber": E, "employeeName": ANN, "leaveTypeId": LTID, "startDate": "2027-03-01", "endDate": "2027-03-01", "days": 1, "reason": "direct"}
made = make("emp", P + "leave_requests", direct)
check("a direct create by an employee is filed under their own login", made.get("employeeId") == LOGIN1 or request(E).get("employeeId") == LOGIN1, (made, request(E)))
check("the employee who created it directly sees it, the other employee does not", E in mine("emp") and E not in mine("emp2"), (sorted(mine("emp")), sorted(mine("emp2"))))
quiet_cancel(E)

# an employee cannot file a request under someone else's login
SP, SPF = "OS" + R, "OF" + R
s, r = call("emp", "POST", P + "leave_requests", dict(direct, requestNumber=SP, employeeId=LOGIN2))
spoof = request(SP)
print("INFO direct create carrying another employee's login: HTTP", s, "stored employeeId:", spoof.get("employeeId"), "response:", str(r)[:160])
check("a direct create under another employee's login is refused and plants nothing", s in (400, 403, 422) and spoof.get("employeeId") != LOGIN2, (s, spoof, r))
quiet_cancel(SP)
s, r = call("emp", "POST", "/api/flows/leave::RequestLeave/execute", dict(direct, requestNumber=SPF, employeeId=LOGIN2))
spoof = request(SPF)
print("INFO flow filing carrying another employee's login: HTTP", s, "stored employeeId:", spoof.get("employeeId"))
check("a flow filing under another employee's login is refused and plants nothing", s in (400, 403, 422) and spoof.get("employeeId") != LOGIN2, (s, spoof, r))
if isinstance(r, dict) and r.get("executionId"):
    call("appr", "POST", f"/api/tasks/{r.get('executionId')}/complete", {"stepName": "await-decision", "outcome": "REJECTED", "values": {"comment": "cleanup"}})

# derived days and the aggregate query, as an employee sees them (A and B are 2 approved days each)
for who in ("hr", "emp", "emp2"):
    s, r = call(who, "GET", "/api/queries/leave::LeaveTakenByEmployee?limit=1000")
    qrows = {x.get("employeeName"): x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else [])}
    mine_name, other_name = (ANN, BEN) if who != "emp2" else (BEN, ANN)
    if who == "hr":
        check("hr's LeaveTakenByEmployee holds both employees, 2 approved days each", all(float(qrows.get(n, {}).get("daysApproved", -1)) == 2 for n in (ANN, BEN)), (s, qrows))
    else:
        print(f"INFO LeaveTakenByEmployee as {who}: HTTP {s}, own row {'visible' if mine_name in qrows else 'not visible'}, "
              f"the other employee's row {'LEAKED (visible)' if other_name in qrows else 'not visible'}, Eva's row {'visible' if 'Eva ' + R in qrows else 'not visible'}")

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
