"""DOM-3: drives the purchasing pack through a booted consumer app (NPDevSamples/dom-purchasing).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<buyer>=dev:buyer:BUYER;<mgr>=dev:mgr:PURCH_MGR;<pay>=dev:pay:PAYABLES;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same five keys:

    python purchasing_probe.py http://127.0.0.1:8422 <admin> <buyer> <mgr> <pay> <plain>

Rows from earlier runs may exist; every code and total here is scoped to rows this run created.
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
KEYS = dict(zip(("admin", "buyer", "mgr", "pay", "plain"), sys.argv[2:7]))
R = uuid.uuid4().hex[:6]
results = []
P = "/api/purchasing_"


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


def move(who, path, rid, status, ok=True):
    s, r = call(who, "PATCH", f"{path}/{rid}", {"status": status})
    if s == 405:   # this runtime updates through PUT
        s, r = call(who, "PUT", f"{path}/{rid}", {"status": status})
    check(f"{who}: -> {status} ({'allowed' if ok else 'refused'})", (s in (200, 201)) if ok else (s in (403, 422)), (s, r))


def rows(query, key, value):
    s, r = call("mgr", "GET", f"/api/queries/purchasing::{query}?limit=1000")
    return [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if x.get(key) == value]


sup = make("mgr", P + "suppliers", {"code": "S" + R, "name": "Acme Supply " + R, "paymentTermsDays": 30, "active": True})
i1 = make("mgr", P + "purchase_items", {"sku": "I1" + R, "description": "Ten", "standardCost": 10, "active": True})
i2 = make("mgr", P + "purchase_items", {"sku": "I2" + R, "description": "Twenty", "standardCost": 20, "active": True})
sid = sup.get("id")

# roles and invariants
refused("a buyer cannot create a supplier", "buyer", P + "suppliers", {"code": "X" + R, "name": "x", "active": True}, (401, 403))
refused("payables cannot create an order", "pay", P + "purchase_orders", {"orderNumber": "A" + R, "supplierId": sid}, (401, 403))
refused("negative payment terms are refused", "mgr", P + "suppliers", {"code": "N" + R, "name": "n", "paymentTermsDays": -1, "active": True})
refused("a negative standard cost is refused", "mgr", P + "purchase_items", {"sku": "N" + R, "description": "n", "standardCost": -1, "active": True})
refused("a duplicate supplier code is refused", "mgr", P + "suppliers", {"code": "S" + R, "name": "dup", "active": True}, (409, 422))

# order and lines
o1 = make("buyer", P + "purchase_orders", {"orderNumber": "PO1" + R, "supplierId": sid})
check("a new order starts as DRAFT", o1.get("status") == "DRAFT", o1.get("status"))
oid = o1.get("id")
l1 = make("buyer", P + "purchase_order_lines", {"orderId": oid, "itemId": i1.get("id"), "quantity": 3, "unitCost": 10})
l2 = make("buyer", P + "purchase_order_lines", {"orderId": oid, "itemId": i2.get("id"), "quantity": 2, "unitCost": 20})
check("line total is derived: 3 x 10 = 30", float(l1.get("lineTotal", -1)) == 30, l1)
refused("quantity 0 is refused", "buyer", P + "purchase_order_lines", {"orderId": oid, "itemId": i1.get("id"), "quantity": 0, "unitCost": 10})
refused("a negative unit cost on a line is refused", "buyer", P + "purchase_order_lines", {"orderId": oid, "itemId": i1.get("id"), "quantity": 1, "unitCost": -5})
refused("a line for a missing order is refused", "buyer", P + "purchase_order_lines", {"orderId": str(uuid.uuid4()), "itemId": i1.get("id"), "quantity": 1, "unitCost": 1})

s, body = call("plain", "GET", P + "purchase_orders")
check("a user with no purchasing role cannot see the order", s in (401, 403) or ("PO1" + R) not in json.dumps(body), s)

# order lifecycle
move("buyer", P + "purchase_orders", oid, "APPROVED")
move("buyer", P + "purchase_orders", oid, "DRAFT", ok=False)
o2 = make("buyer", P + "purchase_orders", {"orderNumber": "PO2" + R, "supplierId": sid})
move("buyer", P + "purchase_orders", o2.get("id"), "CANCELLED")
move("buyer", P + "purchase_orders", o2.get("id"), "APPROVED", ok=False)
move("buyer", P + "purchase_orders", oid, "RECEIVED")
move("buyer", P + "purchase_orders", oid, "CANCELLED", ok=False)

# supplier bills
b1 = make("pay", P + "supplier_bills", {"billNumber": "B1" + R, "orderId": oid, "supplierId": sid, "amount": 70, "dueDate": "2026-12-31T00:00:00Z"})
check("a new bill starts as OPEN", b1.get("status") == "OPEN", b1.get("status"))
b2 = make("pay", P + "supplier_bills", {"billNumber": "B2" + R, "orderId": oid, "supplierId": sid, "amount": 30, "dueDate": "2026-12-31T00:00:00Z"})
refused("a bill for 0 is refused", "pay", P + "supplier_bills", {"billNumber": "Z" + R, "orderId": oid, "supplierId": sid, "amount": 0, "dueDate": "2026-12-31T00:00:00Z"})
refused("a duplicate bill number is refused", "pay", P + "supplier_bills", {"billNumber": "B1" + R, "orderId": oid, "supplierId": sid, "amount": 5, "dueDate": "2026-12-31T00:00:00Z"}, (409, 422))
refused("a buyer cannot record a bill", "buyer", P + "supplier_bills", {"billNumber": "R" + R, "orderId": oid, "supplierId": sid, "amount": 5, "dueDate": "2026-12-31T00:00:00Z"}, (401, 403))
move("buyer", P + "supplier_bills", b1.get("id"), "PAID", ok=False)
move("pay", P + "supplier_bills", b1.get("id"), "PAID")
move("pay", P + "supplier_bills", b1.get("id"), "VOID", ok=False)

# derived views
t = rows("PurchaseOrderTotals", "orderId", oid)
print("PurchaseOrderTotals:", json.dumps(t)[:200])
check("order total = 30 + 40 = 70 over 2 lines", len(t) == 1 and float(t[0].get("total", -1)) == 70 and int(t[0].get("lines", -1)) == 2, t)
owed = rows("AmountOwedToSupplier", "supplierId", sid)
print("AmountOwedToSupplier:", json.dumps(owed)[:240])
check("supplier is owed 30 (the paid 70 is not owed) on 1 open bill, 70 paid",
      len(owed) == 1 and float(owed[0].get("owed", -1)) == 30 and int(owed[0].get("openBills", -1)) == 1 and float(owed[0].get("paid", -1)) == 70, owed)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
