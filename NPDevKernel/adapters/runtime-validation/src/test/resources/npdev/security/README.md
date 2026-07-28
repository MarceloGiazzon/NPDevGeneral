# Test fixtures — not real secrets

`test-jwt-private.pem` / `test-jwt-public.pem` are a throwaway RSA keypair generated solely for
this module's unit tests. They protect nothing, are used by no deployment, and are committed
deliberately so tests run without any setup.

If you found these via secret scanning: this is expected and out of scope. See `SECURITY.md` at
the repo root.
