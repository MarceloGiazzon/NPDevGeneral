# ride-hailing sample

An Uber-style backend built only from a JSON model: riders, drivers, rides with a lifecycle, nearest-driver
matching, driver offers with a time limit, payments, dynamic price, ratings, push and a road route.
It is the yardstick for "can NPDev express a real backend". Port **8150**, `auth.mode=jwt`.

- `Input/` — `model.json`, `config.json`, `db.definition.json`, the local identity pack.
- `mocks/` — stand-ins for the outside world, pure Python: `mock_psp.py` (payments, signed webhooks),
  `mock_push.py` (push gateway), `mock_osrm.py` (road routes). `python -I mocks/test_mocks.py` runs their tests.
- `drive_s2.py` — REST acceptance driver, one PASS/FAIL line per rule:
  `python -I drive_s2.py --base http://localhost:8150 --stages setup,roles`.

Payments are proven against the mock only; a real provider needs your own sandbox keys. Road distance needs an
OSRM server you run. The driver position arrives by polling.
