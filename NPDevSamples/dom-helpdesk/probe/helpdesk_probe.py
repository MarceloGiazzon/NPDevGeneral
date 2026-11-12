"""DOM-4: drives the helpdesk pack through a booted consumer app (NPDevSamples/dom-helpdesk).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<req>=dev:req:REQUESTER;<req2>=dev:req2:REQUESTER;<agent>=dev:agent:HD_AGENT;<mgr>=dev:mgr:HD_MGR;<plain>=dev:plain:USER[;<req3>=dev:req3:REQUESTER]
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same keys, in the same order (req2 is the second requester; req3, the optional
seventh key, is a third requester who never raises a ticket -- without it the "sees nothing" check is skipped):

    python helpdesk_probe.py http://127.0.0.1:8423 <admin> <req> <req2> <agent> <mgr> <plain> [<req3>]

A requester sees only the tickets they raised (pack helpdesk 1.2.0: Ticket access.read scopes the requester role
to requesterId == $user.id); staff roles see every ticket. Each identity's login is discovered from /api/me.

Rows from earlier runs may exist; every code and count here is scoped to rows this run created.
Exit 0 = every check passed.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

if len(sys.argv) not in (8, 9):
    sys.exit(__doc__)
BASE = sys.argv[1]
KEYS = dict(zip(("admin", "req", "req2", "agent", "mgr", "plain", "req3"), sys.argv[2:]))
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


def rows_of(body):
    return body.get("rows", []) if isinstance(body, dict) else (body or [])


def tickets_of(who):
    """(status, body, rows) of the ticket list as `who` sees it."""
    s, b = call(who, "GET", P + "tickets?limit=1000")
    return s, b, rows_of(b)


def query_rows(who, name):
    s, b = call(who, "GET", f"/api/queries/helpdesk::{name}?limit=1000")
    return s, rows_of(b)


# The login each key resolves to is discovered, never assumed: /api/me reports the actor the runtime sees for the key.
LOGIN = {who: (call(who, "GET", "/api/me")[1] or {}).get("actorId") for who in KEYS}


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
# On a wiped database no NEW ticket survives to the end of this run (all are taken or closed), so the NEW group is checked
# here, while t1 is still NEW, instead of after the lifecycle.
by = rows("TicketsByStatus", "status", "NEW")
check("TicketsByStatus has a NEW group", len(by) == 1 and int(by[0].get("tickets", 0)) >= 1, by)
# Pack 1.2.0: a requester reads the ticket list, but only the tickets they raised (this replaces "a requester cannot read the queue").
s_req, _, req_rows = tickets_of("req")
check("a requester can read the ticket list, and every row in it is theirs",
      s_req == 200 and any(x.get("id") == tid for x in req_rows) and all(x.get("requesterId") == LOGIN["req"] for x in req_rows),
      (s_req, [x.get("requesterId") for x in req_rows][:5]))
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
load = rows("WorkloadByAgent", "assignedAgentId", aid)
print("WorkloadByAgent:", json.dumps(load)[:240])
check("the agent holds 1 open ticket (t3) and 1 resolved (t4); t1 is closed",
      len(load) == 1 and int(load[0].get("open", -1)) == 1 and int(load[0].get("resolved", -1)) == 1, load)
nw = (call("mgr", "GET", "/api/queries/helpdesk::NewTickets?limit=1000")[1] or {}).get("rows", [])
check("NewTickets holds none of this run's tickets: every one has been taken or closed", not [x for x in nw if str(x.get("ticketNumber", "")).endswith(R)], nw)

# --- a requester sees only their own tickets (helpdesk 1.2.0) ---
print("logins:", {w: LOGIN[w] for w in ("req", "req2", "req3") if w in LOGIN})
check("every identity resolves to a login through /api/me, and the logins are all different",
      all(LOGIN.get(w) for w in KEYS) and len(set(LOGIN.values())) == len(LOGIN), LOGIN)
tA = make("req", P + "tickets", ticket("A"))
tB = make("req2", P + "tickets", ticket("B"))
aid_A, aid_B = tA.get("id"), tB.get("id")
check("a ticket records the login of whoever raised it (requesterId, filled from the caller)",
      tA.get("requesterId") == LOGIN["req"] and tB.get("requesterId") == LOGIN["req2"]
      and all(t.get("requesterId") == LOGIN["req"] for t in (t1, t2, t3, t4)), (tA.get("requesterId"), tB.get("requesterId")))
s, spoof = call("req", "POST", P + "tickets", ticket("S", requesterId=LOGIN["req2"]))
check("a requester cannot raise a ticket in another requester's name", s in (403, 422), (s, spoof))

for me, other, mine_id, other_id, other_no in (("req", "req2", aid_A, aid_B, "TB" + R), ("req2", "req", aid_B, aid_A, "TA" + R)):
    s, b, mine = tickets_of(me)
    ids = [x.get("id") for x in mine]
    check(f"{me} lists their own ticket and not {other}'s", s == 200 and mine_id in ids and other_id not in ids, (s, ids))
    check(f"{me}'s list holds only rows carrying {me}'s own login", all(x.get("requesterId") == LOGIN[me] for x in mine),
          [x.get("requesterId") for x in mine if x.get("requesterId") != LOGIN[me]])
    if isinstance(b, dict) and "total" in b:
        check(f"{me}'s list total counts only their own rows (no count of {other}'s rows leaks)", b.get("total") == len(mine), (b.get("total"), len(mine)))
    s_own, own = call(me, "GET", f"{P}tickets/{mine_id}")
    check(f"{me} can open their own ticket by id", s_own == 200 and isinstance(own, dict) and own.get("id") == mine_id, (s_own, own))
    s_oth, oth = call(me, "GET", f"{P}tickets/{other_id}")
    check(f"{me} cannot open {other}'s ticket by id", s_oth in (403, 404) and other_no not in json.dumps(oth), (s_oth, oth))
    s_f, f = call(me, "GET", P + "tickets?where=ticketNumber:eq:" + other_no)
    # (this route ignores where=, so the answer is the caller's own rows; the point is that the other's number never shows up)
    check(f"{me} cannot find {other}'s ticket by filtering for its number", s_f != 200 or other_no not in json.dumps(f), (s_f, str(f)[:200]))

for who in ("admin", "agent", "mgr"):
    s, _, seen = tickets_of(who)
    ids = [x.get("id") for x in seen]
    check(f"{who} (staff) sees both requesters' tickets", s == 200 and aid_A in ids and aid_B in ids, (s, len(ids)))
if "req3" in KEYS:
    s, b, none = tickets_of("req3")
    check("a requester who raised nothing sees an empty list", s == 200 and not none and (not isinstance(b, dict) or b.get("total", 0) == 0), (s, b))
check("a requester still cannot read comments (staff only)", call("req", "GET", P + "ticket_comments")[0] in (401, 403), None)

# derived queries over tickets, as a requester: each must be scoped to their own tickets, never the whole queue
for me, other_no in (("req", "TB" + R), ("req2", "TA" + R)):
    _, _, mine = tickets_of(me)
    own_numbers = {str(x.get("ticketNumber")) for x in mine}
    s, nw = query_rows(me, "NewTickets")
    nums = [str(x.get("ticketNumber", "")) for x in nw]
    check(f"derived: NewTickets for {me} holds only their own tickets", s == 200 and other_no not in nums and all(n in own_numbers for n in nums), (s, nums[:8]))
    s, st = query_rows(me, "TicketsByStatus")
    counted = sum(int(x.get("tickets", 0)) for x in st)
    check(f"derived: TicketsByStatus for {me} counts exactly their own tickets", s == 200 and counted == len(mine), (s, counted, len(mine)))
s, nw = query_rows("admin", "NewTickets")
check("derived: NewTickets for admin holds both new tickets", s == 200 and {"TA" + R, "TB" + R} <= {str(x.get("ticketNumber", "")) for x in nw}, (s, len(nw)))
move("agent", aid_B, "OPEN", extra={"assignedAgentId": aid})
for me in ("req", "req2"):
    _, _, mine = tickets_of(me)
    own_open = len([x for x in mine if x.get("status") == "OPEN" and x.get("assignedAgentId") == aid])
    own_resolved = len([x for x in mine if x.get("status") == "RESOLVED" and x.get("assignedAgentId") == aid])
    s, wl = query_rows(me, "WorkloadByAgent")
    mine_wl = [x for x in wl if x.get("assignedAgentId") == aid]
    got_open = int(mine_wl[0].get("open", 0)) if mine_wl else 0
    got_res = int(mine_wl[0].get("resolved", 0)) if mine_wl else 0
    print(f"WorkloadByAgent as {me}:", s, json.dumps(mine_wl)[:200], "own open/resolved:", own_open, own_resolved)
    check(f"derived: WorkloadByAgent for {me} counts only their own open tickets", s == 200 and got_open == own_open, (s, got_open, own_open))
    check(f"derived: WorkloadByAgent's resolved count for {me} counts only their own resolved tickets", s == 200 and got_res == own_resolved, (s, got_res, own_resolved))
_, wl = query_rows("mgr", "WorkloadByAgent")
w = [x for x in wl if x.get("assignedAgentId") == aid]
check("derived: WorkloadByAgent for the manager counts both requesters' open tickets (t3 and B)", len(w) == 1 and int(w[0].get("open", -1)) == 2, w)

# the generic concept route is a second way to read tickets; it must apply the same row scope
for route in ("helpdesk_tickets", "helpdesk::Ticket"):
    s, g = call("admin", "GET", f"/api/concepts/{route}?size=200")
    if s != 200:
        print(f"generic route /api/concepts/{route}: {s} (not a ticket route)")
        continue
    s, g = call("req", "GET", f"/api/concepts/{route}?size=200")
    print(f"generic route /api/concepts/{route} as req:", s, str(g)[:120])
    gr = rows_of(g) if isinstance(g, dict) else []
    if not gr and isinstance(g, dict):
        gr = g.get("items", g.get("content", []))
    gids = [x.get("id") for x in gr]
    check(f"the generic concept route /api/concepts/{route} also shows a requester only their own tickets",
          s in (403, 404) or (aid_A in gids and aid_B not in gids and all(x.get("requesterId") == LOGIN["req"] for x in gr)),
          (s, [x.get("requesterId") for x in gr][:5]))
    break

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
