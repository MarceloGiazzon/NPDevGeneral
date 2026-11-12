"""Drives the approvals pack through a booted consumer app (NPDevSamples/dom-approvals).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<req>=dev:req:REQUESTER;<req2>=dev:req2:REQUESTER;<appr>=dev:appr:APPROVER;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same five keys (two different requesters, so "requestedBy is the filing login" can be
proved to differ per person):

    python approvals_probe.py http://127.0.0.1:8425 <admin> <req> <req2> <appr> <plain>

Rows from earlier runs may exist; every lookup here is scoped to titles this run created.
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
KEYS = dict(zip(("admin", "req", "req2", "appr", "plain"), sys.argv[2:7]))
R = uuid.uuid4().hex[:6]
results = []
REQ = "/api/approvals_approval_requests"
DEC = "/api/approvals_approval_decisions"


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


def listing(who, path):
    s, r = call(who, "GET", path + "?limit=1000")
    return s, (r.get("rows", []) if isinstance(r, dict) else (r or []))


def request(title):
    return next((x for x in listing("admin", REQ)[1] if x.get("title") == title), {})


def file_request(title, who="req", extra=None):
    body = {"title": title, "description": "probe " + R}
    body.update(extra or {})
    s, r = call(who, "POST", "/api/flows/approvals::RequestApproval/execute", body)
    ok = s == 202 and isinstance(r, dict) and r.get("status") == "WAITING_EVENT"
    check(f"{who} files '{title}' through the flow (waits for a decision)", ok, (s, r))
    return (r or {}).get("executionId") if isinstance(r, dict) else None


def my_tasks(who):
    s, r = call(who, "GET", "/api/tasks")
    return (r or {}).get("items", []) if isinstance(r, dict) else []


def decide(who, execution, outcome, ok=True, comment="fine"):
    s, r = call(who, "POST", f"/api/tasks/{execution}/complete", {"stepName": "await-decision", "outcome": outcome, "values": {"comment": comment}})
    check(f"{who} decides {outcome} ({'allowed' if ok else 'refused'})", (s in (200, 201)) if ok else (s in (403, 404, 409, 422)), (s, r))


# ---- roles: who may file
s, fr = call("plain", "POST", "/api/flows/approvals::RequestApproval/execute", {"title": "P " + R})
check("a user with no approvals role cannot file a request (flow.execute denied)", s in (401, 403) or (isinstance(fr, dict) and fr.get("errorCode") == "forbidden"), (s, fr))

# ---- REG-337: a request filed through the flow carries the filing login in requestedBy
T1, T2, T3, T4 = (f"{n} {R}" for n in ("Laptop", "Travel", "Licence", "Course"))
e1 = file_request(T1, "req")
e2 = file_request(T2, "req2")
e3 = file_request(T3, "req")
e4 = file_request(T4, "req2")
r1, r2, r3, r4 = request(T1), request(T2), request(T3), request(T4)
print("requestedBy stored for req / req2:", r1.get("requestedBy"), "/", r2.get("requestedBy"))
check("a new request is PENDING", r1.get("status") == "PENDING", r1)
check("requestedBy is filled on a request filed through the flow (REG-337)", bool(r1.get("requestedBy")) and bool(r2.get("requestedBy")), (r1, r2))
check("the two requesters are recorded as different logins", bool(r1.get("requestedBy")) and r1.get("requestedBy") != r2.get("requestedBy"), (r1.get("requestedBy"), r2.get("requestedBy")))
check("every request one person files carries the same login", r1.get("requestedBy") == r3.get("requestedBy") and r2.get("requestedBy") == r4.get("requestedBy"), (r1, r2, r3, r4))

# ---- a direct REST create gets the same default (reference behaviour the flow must match)
s, made = call("req", "POST", REQ, {"title": "Direct " + R, "description": "rest"})
check("a requester can create a request over REST", s == 201 and isinstance(made, dict), (s, made))
check("a REST create fills requestedBy with the same login as the flow does", bool(made.get("requestedBy")) and made.get("requestedBy") == r1.get("requestedBy"), (made, r1))

# ---- spoofing: who may name another person as the requester? (the pack does not scope this yet; informational)
SP = "Spoof " + R
s, r = call("req", "POST", "/api/flows/approvals::RequestApproval/execute", {"title": SP, "requestedBy": r2.get("requestedBy")})
print("INFO flow filing carrying another requester's login: HTTP", s, "stored requestedBy:", request(SP).get("requestedBy"))

# ---- the approver sees the tasks, the requesters do not
check("the approver sees the four tasks", {e1, e2, e3, e4} <= {t.get("executionId") for t in my_tasks("appr")}, None)
check("a requester sees none of them in My tasks", not ({e1, e2, e3, e4} & {t.get("executionId") for t in my_tasks("req")}), None)

# ---- deciding
decide("req", e1, "APPROVED", ok=False)
check("a refused decision leaves the request PENDING", request(T1).get("status") == "PENDING", request(T1))
decide("appr", e1, "APPROVED", comment="go ahead")
decide("appr", e2, "REJECTED", comment="over budget")
r1, r2 = request(T1), request(T2)
check("T1 is APPROVED with decider and comment recorded", r1.get("status") == "APPROVED" and r1.get("decidedBy") and r1.get("decisionComment") == "go ahead", r1)
check("T2 is REJECTED with the reason recorded", r2.get("status") == "REJECTED" and r2.get("decisionComment") == "over budget", r2)
r3 = request(T3)   # still pending: re-read, the first snapshot predates the decisions
check("requestedBy survives the decision (decided and still-pending requests of one person agree)", bool(r1.get("requestedBy")) and r1.get("requestedBy") == r3.get("requestedBy"), (r1, r3))
decide("appr", e1, "REJECTED", ok=False)   # already answered
check("an answered task is gone from My tasks", not ({e1, e2} & {t.get("executionId") for t in my_tasks("appr")}), None)

# ---- audit rows: one per decision, approver-only
decisions = {d.get("requestId"): d for d in listing("appr", DEC)[1]}
check("the approver sees one decision row per decided request", r1.get("id") in decisions and r2.get("id") in decisions, sorted(decisions))
check("the decision row carries status, decider and comment", decisions.get(r1.get("id"), {}).get("status") == "APPROVED" and decisions.get(r1.get("id"), {}).get("comment") == "go ahead", decisions.get(r1.get("id")))
s, _ = call("req", "GET", DEC + "?limit=10")
check("a requester cannot read the decision rows", s in (401, 403), s)

# ---- lifecycle: a decided request is final
s, r = call("admin", "PATCH", f"{REQ}/{r2.get('id')}", {"status": "APPROVED"})
if s == 405:
    s, r = call("admin", "PUT", f"{REQ}/{r2.get('id')}", {"status": "APPROVED"})
check("a REJECTED request cannot be moved to APPROVED", s in (403, 409, 422), (s, r))

failed = results.count(False)
print(f"\n{len(results) - failed}/{len(results)} checks passed")
sys.exit(1 if failed else 0)
