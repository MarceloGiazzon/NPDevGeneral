"""DOM-4: drives the helpdesk pack through a booted consumer app (NPDevSamples/dom-helpdesk).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<req>=dev:req:REQUESTER;<agent>=dev:agent:HD_AGENT;<mgr>=dev:mgr:HD_MGR;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same five keys:

    python helpdesk_probe.py http://127.0.0.1:8423 <admin> <req> <agent> <mgr> <plain>

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
KEYS = dict(zip(("admin", "req", "agent", "mgr", "plain"), sys.argv[2:7]))
R = uuid.uuid4().hex[:6]
results = []
P = "/api/helpdesk_"


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


def move(who, rid, status, ok=True, extra=None):
    body = dict(extra or {}, status=status)
    s, r = call(who, "PATCH", f"{P}tickets/{rid}", body)
    if s == 405:   # this runtime updates through PUT
        s, r = call(who, "PUT", f"{P}tickets/{rid}", body)
    check(f"{who}: -> {status} ({'allowed' if ok else 'refused'})", (s in (200, 201)) if ok else (s in (403, 422)), (s, r))


def rows(query, key, value):
    s, r = call("mgr", "GET", f"/api/queries/helpdesk::{query}?limit=1000")
    return [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if x.get(key) == value]


cat = make("mgr", P + "ticket_categories", {"code": "C" + R, "name": "Billing " + R, "active": True})
ag = make("mgr", P + "helpdesk_agents", {"fullName": "Ada " + R, "email": f"ada{R}@example.test", "active": True})
cid, aid = cat.get("id"), ag.get("id")


def ticket(n, **kw):
    body = {"ticketNumber": f"T{n}{R}", "categoryId": cid, "requesterName": "Rita", "requesterEmail": "rita@example.test", "subject": "Cannot log in " + n}
    body.update(kw)
    return body


# roles
refused("a requester cannot create an agent", "req", P + "helpdesk_agents", {"fullName": "x", "email": f"x{R}@example.test", "active": True}, (401, 403))
refused("an agent cannot create a category", "agent", P + "ticket_categories", {"code": "X" + R, "name": "x", "active": True}, (401, 403))
refused("a duplicate agent email is refused", "mgr", P + "helpdesk_agents", {"fullName": "dup", "email": f"ada{R}@example.test", "active": True}, (409, 422))
refused("a duplicate category code is refused", "mgr", P + "ticket_categories", {"code": "C" + R, "name": "dup", "active": True}, (409, 422))

# a requester raises a ticket
t1 = make("req", P + "tickets", ticket("1", priority="HIGH"))
check("a new ticket starts as NEW", t1.get("status") == "NEW", t1.get("status"))
tid = t1.get("id")
check("a requester cannot read the queue", call("req", "GET", P + "tickets")[0] in (401, 403), None)
check("a user with no helpdesk role cannot see the ticket", (lambda s, b: s in (401, 403) or ("T1" + R) not in json.dumps(b))(*call("plain", "GET", P + "tickets")), None)
refused("a ticket without a subject is refused", "req", P + "tickets", {k: v for k, v in ticket("2").items() if k != "subject"})
refused("an unknown priority is refused", "req", P + "tickets", ticket("3", priority="WHENEVER"))
refused("a ticket for a missing category is refused", "req", P + "tickets", ticket("4", categoryId=str(uuid.uuid4())))
refused("a duplicate ticket number is refused", "req", P + "tickets", ticket("1"), (409, 422))
c1 = make("req", P + "ticket_comments", {"ticketId": tid, "authorName": "Rita", "body": "It says my password is wrong."})
refused("a comment on a missing ticket is refused", "req", P + "ticket_comments", {"ticketId": str(uuid.uuid4()), "authorName": "x", "body": "x"})
refused("an empty comment is refused", "req", P + "ticket_comments", {"ticketId": tid, "authorName": "Rita"})
s, cb = call("agent", "GET", P + "ticket_comments?limit=1000")
crow = cb.get("rows", []) if isinstance(cb, dict) else (cb or [])
check("an agent can read the comment", s == 200 and any(x.get("id") == c1.get("id") for x in crow), (s, cb))
move("req", tid, "OPEN", ok=False, extra={"assignedAgentId": aid})

# lifecycle and the assigned-agent rule
move("agent", tid, "OPEN", ok=False)                       # no agent assigned yet
move("agent", tid, "OPEN", extra={"assignedAgentId": aid})
move("agent", tid, "NEW", ok=False)
move("agent", tid, "PENDING")
move("agent", tid, "OPEN")
move("agent", tid, "CLOSED", ok=False)                     # must be resolved first
move("agent", tid, "RESOLVED")
move("agent", tid, "OPEN")                                 # reopened
move("agent", tid, "RESOLVED")
move("agent", tid, "CLOSED")
move("mgr", tid, "OPEN", ok=False)                         # CLOSED is final

t2 = make("req", P + "tickets", ticket("5"))
move("agent", t2.get("id"), "CLOSED")                      # close without action straight from NEW
t3 = make("req", P + "tickets", ticket("6"))
t4 = make("req", P + "tickets", ticket("7"))
move("agent", t3.get("id"), "OPEN", extra={"assignedAgentId": aid})
move("agent", t4.get("id"), "OPEN", extra={"assignedAgentId": aid})
move("agent", t4.get("id"), "RESOLVED")

# derived views
by = rows("TicketsByStatus", "status", "NEW")
check("TicketsByStatus has a NEW group", len(by) == 1 and int(by[0].get("tickets", 0)) >= 1, by)
load = rows("WorkloadByAgent", "assignedAgentId", aid)
print("WorkloadByAgent:", json.dumps(load)[:240])
check("the agent holds 1 open ticket (t3) and 1 resolved (t4); t1 is closed",
      len(load) == 1 and int(load[0].get("open", -1)) == 1 and int(load[0].get("resolved", -1)) == 1, load)
nw = (call("mgr", "GET", "/api/queries/helpdesk::NewTickets?limit=1000")[1] or {}).get("rows", [])
check("NewTickets holds none of this run's tickets: every one has been taken or closed", not [x for x in nw if str(x.get("ticketNumber", "")).endswith(R)], nw)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
