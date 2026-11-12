"""DOM-2: drives the sales pack through a booted consumer app (NPDevSamples/dom-sales).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<rep>=dev:rep:REP;<mgr>=dev:mgr:SALES_MGR;<acct>=dev:acct:ACCT;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same five keys:

    python sales_probe.py http://127.0.0.1:8421 <admin> <rep> <mgr> <acct> <plain>

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
KEYS = dict(zip(("admin", "rep", "mgr", "acct", "plain"), sys.argv[2:7]))
R = uuid.uuid4().hex[:6]
results = []
P = "/api/sales_"


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
    s, r = call("mgr", "GET", f"/api/queries/sales::{query}?limit=1000")
    return [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if x.get(key) == value]


# Since sales 2.0.0 the organization lives in the contacts pack and the product in the products pack;
# a customer and a price-list item are satellites that point back at them.
org = make("mgr", "/api/contacts_organizations", {"name": "Acme " + R, "kind": "CUSTOMER", "active": True})
org2 = make("mgr", "/api/contacts_organizations", {"name": "Other " + R, "kind": "CUSTOMER", "active": True})
p1 = make("mgr", "/api/products_products", {"sku": "I1" + R, "name": "Ten", "kind": "GOOD", "unit": "UNIT", "active": True})
p2 = make("mgr", "/api/products_products", {"sku": "I2" + R, "name": "Twenty", "kind": "GOOD", "unit": "UNIT", "active": True})
p3 = make("mgr", "/api/products_products", {"sku": "I3" + R, "name": "Spare", "kind": "GOOD", "unit": "UNIT", "active": True})
cust = make("mgr", P + "customers", {"code": "C" + R, "organizationId": org.get("id"), "creditLimit": 1000, "active": True})
i1 = make("mgr", P + "sales_items", {"productId": p1.get("id"), "unitPrice": 10, "active": True})
i2 = make("mgr", P + "sales_items", {"productId": p2.get("id"), "unitPrice": 20, "active": True})
cid = cust.get("id")
check("the customer points at its organization", cust.get("organizationId") == org.get("id"), cust)
s, body = call("rep", "GET", "/api/contacts_organizations/" + str(org.get("id")))
check("a rep can read the customer's organization", s == 200, s)

# roles
refused("a rep cannot create a customer", "rep", P + "customers", {"code": "X" + R, "organizationId": org2.get("id"), "active": True}, (401, 403))
refused("an accountant cannot create an order", "acct", P + "sales_orders", {"orderNumber": "A" + R, "customerId": cid}, (401, 403))
refused("a negative credit limit is refused", "mgr", P + "customers", {"code": "N" + R, "organizationId": org2.get("id"), "creditLimit": -1, "active": True})
refused("a negative price is refused", "mgr", P + "sales_items", {"productId": p3.get("id"), "unitPrice": -1, "active": True})
refused("a duplicate customer code is refused", "mgr", P + "customers", {"code": "C" + R, "organizationId": org2.get("id"), "active": True}, (409, 422))
refused("an organization has at most one customer record", "mgr", P + "customers", {"code": "D" + R, "organizationId": org.get("id"), "active": True}, (409, 422))
refused("a customer for a missing organization is refused", "mgr", P + "customers", {"code": "M" + R, "organizationId": str(uuid.uuid4()), "active": True}, (404, 409, 422))

# order and lines
o1 = make("rep", P + "sales_orders", {"orderNumber": "SO1" + R, "customerId": cid})
check("a new order starts as DRAFT", o1.get("status") == "DRAFT", o1.get("status"))
oid = o1.get("id")
l1 = make("rep", P + "sales_order_lines", {"orderId": oid, "productId": p1.get("id"), "quantity": 3, "unitPrice": 10})
l2 = make("rep", P + "sales_order_lines", {"orderId": oid, "productId": p2.get("id"), "quantity": 2, "unitPrice": 20})
check("line total is derived: 3 x 10 = 30", float(l1.get("lineTotal", -1)) == 30, l1)
refused("quantity 0 is refused", "rep", P + "sales_order_lines", {"orderId": oid, "productId": p1.get("id"), "quantity": 0, "unitPrice": 10})
refused("a negative unit price on a line is refused", "rep", P + "sales_order_lines", {"orderId": oid, "productId": p1.get("id"), "quantity": 1, "unitPrice": -5})
refused("a line for a missing order is refused", "rep", P + "sales_order_lines", {"orderId": str(uuid.uuid4()), "productId": p1.get("id"), "quantity": 1, "unitPrice": 1})

s, body = call("plain", "GET", P + "sales_orders")
check("a user with no sales role cannot see the order", s in (401, 403) or ("SO1" + R) not in json.dumps(body), s)

# order lifecycle
move("rep", P + "sales_orders", oid, "CONFIRMED")
move("rep", P + "sales_orders", oid, "DRAFT", ok=False)
o2 = make("rep", P + "sales_orders", {"orderNumber": "SO2" + R, "customerId": cid})
move("rep", P + "sales_orders", o2.get("id"), "CANCELLED")
move("rep", P + "sales_orders", o2.get("id"), "CONFIRMED", ok=False)
move("rep", P + "sales_orders", oid, "INVOICED")
move("rep", P + "sales_orders", oid, "CANCELLED", ok=False)

# invoices
inv1 = make("acct", P + "invoices", {"invoiceNumber": "INV1" + R, "orderId": oid, "customerId": cid, "amount": 70, "dueDate": "2026-12-31T00:00:00Z"})
check("a new invoice starts as ISSUED", inv1.get("status") == "ISSUED", inv1.get("status"))
inv2 = make("acct", P + "invoices", {"invoiceNumber": "INV2" + R, "orderId": oid, "customerId": cid, "amount": 30, "dueDate": "2026-12-31T00:00:00Z"})
refused("an invoice for 0 is refused", "acct", P + "invoices", {"invoiceNumber": "Z" + R, "orderId": oid, "customerId": cid, "amount": 0, "dueDate": "2026-12-31T00:00:00Z"})
refused("a duplicate invoice number is refused", "acct", P + "invoices", {"invoiceNumber": "INV1" + R, "orderId": oid, "customerId": cid, "amount": 5, "dueDate": "2026-12-31T00:00:00Z"}, (409, 422))
refused("a rep cannot raise an invoice", "rep", P + "invoices", {"invoiceNumber": "R" + R, "orderId": oid, "customerId": cid, "amount": 5, "dueDate": "2026-12-31T00:00:00Z"}, (401, 403))
move("rep", P + "invoices", inv1.get("id"), "PAID", ok=False)
move("acct", P + "invoices", inv1.get("id"), "PAID")
move("acct", P + "invoices", inv1.get("id"), "VOID", ok=False)

# derived views
t = rows("OrderTotals", "orderId", oid)
print("OrderTotals:", json.dumps(t)[:200])
check("order total = 30 + 40 = 70 over 2 lines", len(t) == 1 and float(t[0].get("total", -1)) == 70 and int(t[0].get("lines", -1)) == 2, t)
owed = rows("AmountOwedByCustomer", "customerId", cid)
print("AmountOwedByCustomer:", json.dumps(owed)[:240])
check("customer owes 30 (the paid 70 is not owed) on 1 open invoice, 70 paid",
      len(owed) == 1 and float(owed[0].get("owed", -1)) == 30 and int(owed[0].get("openInvoices", -1)) == 1 and float(owed[0].get("paid", -1)) == 70, owed)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
