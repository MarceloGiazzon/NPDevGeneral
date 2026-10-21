"""DOM-1: drives the inventory pack through a booted consumer app (NPDevSamples/dom-inventory).

The app must boot with one static key per identity, so the roles can be exercised without a login flow:

    NPDEV_AUTH_API_KEYS=<admin>=dev:admin:ADMIN;<keeper>=dev:keeper:KEEPER;<manager>=dev:manager:WH_MANAGER;<plain>=dev:plain:USER
    (also exported as NPDEV_AUTH_APIKEYS)

and this script is given the same four keys:

    python inventory_probe.py http://127.0.0.1:8420 <admin> <keeper> <manager> <plain>

Rows from earlier runs may exist; every code, sku and total here is scoped to rows this run created.
Exit 0 = every check passed.
"""
import json
import sys
import uuid
R = uuid.uuid4().hex[:6]
import urllib.error
import urllib.request

if len(sys.argv) != 6:
    sys.exit(__doc__)
BASE = sys.argv[1]
KEYS = dict(zip(("admin", "keeper", "manager", "plain"), sys.argv[2:6]))
results = []


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


wh = make("manager", "/api/inventory_warehouses", {"code": "MAIN" + R, "name": "Main warehouse", "active": True})
a1 = make("manager", "/api/inventory_storage_locations", {"warehouseId": wh.get("id"), "code": "A1", "kind": "STORAGE", "active": True})
b1 = make("manager", "/api/inventory_storage_locations", {"warehouseId": wh.get("id"), "code": "B1", "kind": "PICKING", "active": True})
prod = make("manager", "/api/inventory_products", {"sku": "SKU-" + R, "name": "Widget", "unit": "UNIT", "active": True})
pid, la, lb = prod.get("id"), a1.get("id"), b1.get("id")

s, r = call("manager", "POST", "/api/inventory_storage_locations", {"warehouseId": wh.get("id"), "code": "A1", "kind": "STORAGE", "active": True})
check("a duplicate location code in the same warehouse is refused", s in (409, 422), (s, r))
s, r = call("manager", "POST", "/api/inventory_products", {"sku": "NEG" + R, "name": "n", "unit": "UNIT", "active": True, "minimumStock": -1})
check("a negative minimum stock is refused", s == 422, (s, r))
# role enforcement
s, _ = call("keeper", "POST", "/api/inventory_warehouses", {"code": "X", "name": "x", "active": True})
check("keeper cannot create a warehouse", s in (401, 403), s)
s, _ = call("plain", "GET", "/api/inventory_stock_movements")
check("a user with neither role cannot read movements", s in (401, 403), s)
s, _ = call("keeper", "GET", "/api/inventory_products")
check("keeper can read products", s == 200, s)

# invariants
s, r = call("keeper", "POST", "/api/inventory_stock_movements", {"type": "RECEIPT", "productId": pid, "quantity": 5})
check("RECEIPT without a destination is refused (422)", s == 422, (s, r))
s, r = call("keeper", "POST", "/api/inventory_stock_movements", {"type": "ISSUE", "productId": pid, "quantity": 5, "toLocationId": la})
check("ISSUE without a source is refused (422)", s == 422, (s, r))
s, r = call("keeper", "POST", "/api/inventory_stock_movements", {"type": "TRANSFER", "productId": pid, "quantity": 5, "fromLocationId": la, "toLocationId": la})
check("TRANSFER to the same location is refused (422)", s == 422, (s, r))
s, r = call("keeper", "POST", "/api/inventory_stock_movements", {"type": "RECEIPT", "productId": pid, "quantity": 0, "toLocationId": la})
check("quantity 0 is refused (422)", s == 422, (s, r))


def move(body):
    m = make("keeper", "/api/inventory_stock_movements", body)
    check("a new movement starts as DRAFT", m.get("status") == "DRAFT", m.get("status"))
    return m.get("id")


def set_status(mid, status, expect=200):
    s, r = call("keeper", "PATCH", f"/api/inventory_stock_movements/{mid}", {"status": status})
    if s not in (200, 201, 422):
        s2, r2 = call("keeper", "PUT", f"/api/inventory_stock_movements/{mid}", {"status": status})
        s, r = s2, r2
    ok = (s in (200, 201)) if expect == 200 else (s == 422)
    check(f"-> {status} ({'allowed' if expect == 200 else 'refused'})", ok, (s, r))


m1 = move({"type": "RECEIPT", "productId": pid, "quantity": 100, "toLocationId": la})
m2 = move({"type": "ISSUE", "productId": pid, "quantity": 30, "fromLocationId": la})
m3 = move({"type": "TRANSFER", "productId": pid, "quantity": 20, "fromLocationId": la, "toLocationId": lb})
m4 = move({"type": "RECEIPT", "productId": pid, "quantity": 999, "toLocationId": la})   # stays DRAFT
m5 = move({"type": "RECEIPT", "productId": pid, "quantity": 50, "toLocationId": lb})    # posted then reversed
for mid in (m1, m2, m3, m5):
    set_status(mid, "POSTED")
set_status(m5, "REVERSED")
set_status(m4, "REVERSED", expect=422)     # DRAFT -> REVERSED is not a transition
set_status(m5, "POSTED", expect=422)       # REVERSED is terminal

s, r = call("keeper", "GET", "/api/queries/inventory::StockByProduct")
rows = [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if x.get("productId") == pid]
print("StockByProduct:", json.dumps(rows)[:300])
check("StockByProduct onHand = 100 - 30 = 70 (draft and reversed excluded)",
      len(rows) == 1 and abs(float(rows[0].get("onHand", -1)) - 70) < 0.001, rows)
s, r = call("keeper", "GET", "/api/queries/inventory::StockByProductLocation")
rows = [x for x in ((r or {}).get("rows", []) if isinstance(r, dict) else []) if x.get("productId") == pid]
print("StockByProductLocation:", json.dumps(rows)[:400])
by = {str(x.get("toLocationId")): float(x.get("onHand", 0)) for x in rows}
check("A1 holds 100 - 30 - 20 = 50", abs(by.get(str(la), -1) - 50) < 0.001, by)
check("B1 holds the 20 transferred in (the reversed 50 is gone)", abs(by.get(str(lb), -1) - 20) < 0.001, by)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
