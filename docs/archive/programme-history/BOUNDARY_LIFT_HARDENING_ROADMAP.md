# NPDev — Boundary-Lift Hardening Roadmap

> **Generated:** 2026-07-13 · **Branch at capture:** `beta1-vision-spine`
> **Objective:** Close the two gaps left open after the LIFT-* work (committed in `a63ba7f`) —
> **(1)** ARCH-upload is currently **dev-only** (filesystem adapter only; no prod object store, no
> file lifecycle), and **(2)** the download path is a **stored-XSS vector** (`Content-Disposition:
> inline` + caller-supplied content-type). When this roadmap closes, the file-upload primitive is
> production-safe end to end.
>
> Companion to [BOUNDARY_LIFT_ROADMAP.md](BOUNDARY_LIFT_ROADMAP.md) (which lifted ARCH-upload for dev).
> Same authoring contract: every item carries a **stable ID** and the same six fields
> (What / Where / Why / How / Definition of Done / Verify), plus an explicit **Effort** line.

---

## 0. How to read this document (agent instructions)

- Stable IDs: three features — `HARDEN-DL` (download XSS), `HARDEN-OBJSTORE` (prod object store),
  `HARDEN-GC` (file lifecycle / orphan cleanup); each ships in numbered **phases**.
- **Status vocabulary:** `OPEN` · `IN-PROGRESS` · `DONE` (verified) · `BLOCKED`.
- **Effort unit:** *ideal engineering-days* (focused, one competent implementer, excluding review/CI
  wait). Ranges reflect low→high uncertainty. **Risk** ∈ Low / Med / High.
- **Global rules (unchanged from the prior roadmaps):** restage jars after kernel/adapter/generator
  Java changes (`sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs`);
  schema edits mirror ×4; build output → `D:\WorkSpace\NPDev\Build`; `npdev-generated/` is hash-guarded.
- **Verification bar:** JUnit/adapter tests green + the runtime-host code proven via the
  `TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest` generate→compile→boot path (runtime-host
  has no standalone Gradle build). `HARDEN-OBJSTORE` additionally needs a live MinIO/S3 round-trip.
- **Pre-commit note:** the workspace-slimness hook fails if a rebuildable `node_modules/` lands under
  `scripts/` — keep `scripts/quality/json-schema-validator/node_modules` out of the tree (it is
  gitignored; delete it before committing if a tool recreates it).

---

## 1. Effort summary (what you asked for)

| Feature | Gap it closes | Phases | Effort (ideal-days) | Risk | Priority |
|---|---|---|---|---|---|
| **HARDEN-DL** | Download stored-XSS | P1–P3 | **1.0 – 1.5** | Low | **P1 — do first** |
| **HARDEN-OBJSTORE** | ARCH-upload dev-only → prod object store | P1–P4 | **3.0 – 4.5** | Med | P2 |
| **HARDEN-GC** | Orphaned file bytes (no lifecycle) | P1–P4 | **3.0 – 6.0** | Med-High | P3 |
| | | **Total** | **≈ 7 – 12 ideal-days** (~1.5–2.5 calendar weeks incl. review/CI) | | |

**Fastest path to "safe enough for prod pilot":** HARDEN-DL (the security fix, ~1 day) + HARDEN-OBJSTORE
P1–P2 (adapter + config, ~2–3 days). HARDEN-GC can trail as a fast-follow — orphaned bytes are a cost/
hygiene issue, not a correctness or security hole, so it need not gate a first prod deployment.

**Why the ranges:** HARDEN-DL is bounded and mechanical (low end likely). HARDEN-OBJSTORE's spread is
the first external SDK dependency in a kernel adapter + Testcontainers/Docker for the live test.
HARDEN-GC's spread is almost entirely **P3 (orphan sweep for failed uploads)** — the reference-tracking
/ two-phase-commit design is the only genuinely open design question in this roadmap.

---

## 2. HARDEN-DL — Safe file download (closes the stored-XSS vector)

**Gap today:** [`FileUploadController.download`](../../../NPDevRuntimeHost/src/main/java/com/finalexec/api/FileUploadController.java)
serves bytes with `Content-Disposition: inline` and a **caller-supplied** `contentType` query param.
An attacker who can get an HTML/SVG file past a permissive `contentTypes` allowlist can serve
active content from the app's own origin → stored XSS (session theft, CSRF, etc.). Tenant isolation
is already correct; this is purely about how bytes are *served back*.

### HARDEN-DL-P1 — Serve the stored content-type, not a caller-supplied one
- **Status:** OPEN · **Effort:** 0.5 day · **Risk:** Low
- **What:** Stop trusting the `contentType`/`originalName` download query params; resolve them from the
  persisted `FileHandle` instead (the upload path already validated content-type against the field's
  allowlist and `FileHandle` already carries `contentType`/`originalName`).
- **Where:** `FileUploadController.download` (drop the `contentType`/`originalName` `@RequestParam`s);
  the source of truth is the handle persisted on the record. If the controller can't read the record,
  persist enough in the store key/sidecar to recover it, or have the client send back the *whole*
  handle it received at upload (still server-validated) rather than a free-form type.
- **Why:** A caller-controlled response content-type is half the XSS primitive; removing it means the
  browser only ever sees a type the upload allowlist permitted.
- **How:** resolve `FileHandle` (from record or a store `head`), set response type from it; reject a
  download whose supplied handle fields don't match what's stored.
- **Definition of Done:** download response `Content-Type` derives from the stored handle; a request
  that tampers the content-type param cannot change what's served.
- **Verify:** controller test asserts served type == stored type regardless of the request param.

### HARDEN-DL-P2 — Force attachment + anti-sniff + CSP on the file route
- **Status:** OPEN · **Effort:** 0.5 day · **Risk:** Low
- **What:** Serve `Content-Disposition: attachment` for everything except an explicit inline-safe
  allowlist (images, PDF), add `X-Content-Type-Options: nosniff`, and a locked-down
  `Content-Security-Policy` (e.g. `default-src 'none'; sandbox`) on `/api/files/**`.
- **Where:** `FileUploadController.download` response headers; optionally a `WebMvcConfigurer`/filter
  scoped to `/api/files/**` so the CSP/headers can't be forgotten on a future endpoint.
- **Why:** `attachment` stops the browser rendering the file in-origin; `nosniff` stops MIME
  upgrade; the CSP neuters any script even if a type slips through. Defense in depth over P1.
- **How:** inline-safe set = `{image/png,image/jpeg,image/gif,image/webp,application/pdf}` → `inline`,
  else `attachment`; always `nosniff` + the sandbox CSP.
- **Definition of Done:** a non-image download returns `attachment` + `nosniff` + CSP; images/PDF may
  stay `inline` but still carry `nosniff` + CSP.
- **Verify:** controller test asserts the headers per content-type class.

### HARDEN-DL-P3 — Active-content proof test
- **Status:** BLOCKED on P1,P2 · **Effort:** 0.25 day · **Risk:** Low
- **What:** A test that uploads an HTML/SVG payload (via a field whose allowlist permits it) and
  asserts the download is served as inert `attachment` (+ `nosniff` + CSP), never `inline` HTML.
- **Where:** the packaged-app proof fixture (`TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest`)
  or a `FileUploadController` slice test.
- **Why:** Pins the regression so a future "just make it preview inline" change can't silently
  reopen the hole.
- **Definition of Done:** the XSS payload round-trips as an attachment; test fails if served inline.
- **Verify:** the new test green in the established proof path.

---

## 3. HARDEN-OBJSTORE — Production object-store adapter (closes "dev-only")

**Gap today:** the locked file-storage decision was **inproc filesystem (dev) + S3-compatible object
store (prod)**; only [`file-store-inproc`](../../../NPDevKernel/adapters/file-store-inproc) was built. There
is no adapter suitable for a horizontally-scaled prod deployment (the filesystem adapter is
single-node and not durable across container restarts).

### HARDEN-OBJSTORE-P1 — S3-compatible adapter module
- **Status:** OPEN · **Effort:** 1.5 – 2.5 days · **Risk:** Med
- **What:** New `file-store-objectstore` adapter implementing
  [`FileStoreContract`](../../../NPDevKernel/kernel/src/main/java/com/npdev/kernel/ports/FileStoreContract.java)
  against S3-compatible storage (works with AWS S3, MinIO, Cloudflare R2, GCS-S3).
- **Where:** new module `NPDevKernel/adapters/file-store-objectstore` (mirror `file-store-inproc`'s
  layout + `build.gradle`); register in `NPDevKernel/settings.gradle`; add the AWS SDK v2 S3
  dependency (the **first external SDK in a kernel adapter** — keep it isolated to this module).
- **Why:** Durable, multi-node, offloaded storage is the prod half of the decision.
- **How:**
  1. Implement `put` (streaming; use S3 multipart-upload for large files so nothing buffers fully in
     memory), `get` (stream to the caller's `OutputStream`), `delete`, using the same tenant-prefixed
     key scheme (`<tenantId>/<uuid>`) and the same path-traversal safety as the inproc adapter.
  2. Preserve `FileHandle` semantics (storeId identifies the bucket/store; key is the object key).
  3. Map SDK not-found → `NoSuchElementException` (parity with inproc, so the controller's 404 path is
     unchanged).
- **Definition of Done:** `put→get→delete` round-trips against a real S3-compatible endpoint; large
  (>5MB) files stream without OOM; keys tenant-prefixed; missing key → `NoSuchElementException`.
- **Verify:** adapter unit tests with a mocked S3 client + the P3 integration test below.

### HARDEN-OBJSTORE-P2 — Config-driven adapter selection
- **Status:** BLOCKED on P1 · **Effort:** 0.5 day · **Risk:** Low
- **What:** Choose the adapter by config: `npdev.filestore.provider: inproc | objectstore`, with
  endpoint/region/bucket/credentials for the object-store path.
- **Where:** [`NpdevFileStoreConfig`](../../../NPDevRuntimeHost/runtimehost-core/src/main/java/com/finalexec/config/NpdevFileStoreConfig.java)
  (`@ConditionalOnProperty` bean selection); `application.yml` (defaults `inproc`); credentials from
  env/secret, never committed.
- **Why:** One binary serves dev (filesystem) and prod (object store) by config only.
- **How:** conditional beans; validate required object-store props are present when selected (fail
  fast at boot with a clear message otherwise).
- **Definition of Done:** default profile uses inproc unchanged; setting `provider: objectstore` +
  endpoint boots against the object store; missing config fails fast with a named error.
- **Verify:** boot both configurations (packaged-app proof for inproc; the P3 live test for objectstore).

### HARDEN-OBJSTORE-P3 — Live integration test (MinIO/Testcontainers)
- **Status:** BLOCKED on P1 · **Effort:** 0.5 – 1 day · **Risk:** Med (needs Docker)
- **What:** An integration test running the adapter against a real MinIO container.
- **Where:** `file-store-objectstore` test source; Testcontainers MinIO module.
- **Why:** A mocked S3 client can't catch multipart/streaming/credential/endpoint-path-style bugs.
- **How:** Testcontainers MinIO; put/get/delete + a large streamed object; tag it so CI without Docker
  can skip cleanly (don't fail the whole gate where Docker is absent).
- **Definition of Done:** green against MinIO; large-file streaming verified; skips gracefully without
  Docker.
- **Verify:** the test green locally with Docker; CI wiring documented.

### HARDEN-OBJSTORE-P4 — Live app proof
- **Status:** BLOCKED on P2,P3 · **Effort:** 0.5 day · **Risk:** Low
- **What:** Generate an app configured for `objectstore`, boot it, upload+download a file end to end.
- **Where:** a live generate→build→run on an H2 sample pointed at MinIO (127.0.0.1; real tenant, never
  `"default"`).
- **Why:** Proves the full path (controller → config → adapter → object store) in a real app.
- **Definition of Done:** upload returns a handle; download returns identical bytes; bytes exist in the
  bucket under the tenant prefix.
- **Verify:** live REST round-trip evidence in `NPDev_General__OutsideRepo`.

---

## 4. HARDEN-GC — File lifecycle / orphan cleanup (closes leaked bytes)

**Gap today:** documented in `FileUploadController` — deleting or replacing a record never deletes its
file bytes, and an upload whose record-save never completes leaks too (upload is a separate call from
the CRUD save). Bytes accumulate forever. Not a correctness/security hole, but an unbounded cost/
hygiene problem.

### HARDEN-GC-P1 — Delete-cascade on record delete
- **Status:** OPEN · **Effort:** 1 – 2 days · **Risk:** Med (cross-cutting)
- **What:** When a record is deleted, delete every file handle it holds (across all `file` fields,
  incl. `multiple`).
- **Where:** the generated CRUD delete path
  ([`service-base.mustache`](../../../NPDevGenerator/generator/src/main/resources/npdev-templates/service-base.mustache))
  or a kernel-level post-delete hook; needs the compiled model's per-concept `file`-field list at
  runtime + the `FileStoreContract` bean; tenant-scoped.
- **Why:** The dominant orphan source; the most valuable single slice.
- **How:** enumerate the concept's file fields, read the handle(s) off the row being deleted, call
  `fileStore.delete` for each; tolerate already-missing (idempotent). Order so a store-delete failure
  doesn't abort the record delete (log + continue; the sweep in P3 catches stragglers).
- **Definition of Done:** deleting a record with a file field removes the underlying bytes; a
  multi-file field removes all; deleting twice is safe.
- **Verify:** test on inproc (assert bytes gone) via the proof path; live check.

### HARDEN-GC-P2 — Replace-cascade on file-field update
- **Status:** BLOCKED on P1 · **Effort:** 0.5 – 1 day · **Risk:** Med (read-before-write)
- **What:** When an update replaces a file field's value, delete the *previous* handle.
- **Where:** the generated CRUD update path (needs the pre-update row to diff old vs new handle).
- **Why:** Re-uploading over a field otherwise leaks the old object every time.
- **How:** read-before-write the old handle; if the new value differs, delete the old after a
  successful save (never before — a failed save must not orphan the still-referenced old file).
- **Definition of Done:** replacing a file field deletes the old bytes and keeps the new; a failed
  save leaves the old bytes intact.
- **Verify:** test: replace → old gone, new present; failed-save → old intact.

### HARDEN-GC-P3 — Orphan sweep for failed/abandoned uploads
- **Status:** BLOCKED on P1 · **Effort:** 1 – 2 days · **Risk:** Med-High (only real design question)
- **What:** Reclaim bytes uploaded but never referenced by a saved record (client uploaded, then the
  create/update never happened or failed).
- **Where:** a new janitor/sweep service in RuntimeHost + a small upload-tracking mechanism.
- **Why:** The upload endpoint stores bytes *before* the record exists, so a dropped form leaks.
- **How — pick one (decision to make here):**
  - **(a) TTL sweep (simpler):** record `uploadedAt` per key; a scheduled job deletes keys older than
    a grace window (e.g. 24h) that no record references. Needs a "is this key referenced?" scan or an
    index. Lowest complexity, eventual cleanup, small window of over-retention.
  - **(b) Two-phase (stronger):** uploads land in a `pending/` prefix; the record-save "confirms" the
    handle (move/tag to `committed/`); a sweep reaps unconfirmed `pending/` past the grace window.
    Cleaner guarantee, more moving parts (confirm hook in CRUD save, move/tag op in the adapter).
  - Recommendation: **(a)** first (fast, good-enough), consider **(b)** if pending-object volume or
    compliance demands a hard guarantee.
- **Definition of Done:** an uploaded-but-unreferenced file is reclaimed after the grace window; a
  referenced file is never reclaimed.
- **Verify:** test simulating an abandoned upload → swept; a referenced file → retained.

### HARDEN-GC-P4 — Tests + live verification
- **Status:** BLOCKED on P1–P3 · **Effort:** 0.5 – 1 day · **Risk:** Low
- **What:** Consolidated lifecycle tests + a live delete/replace/abandon check on a generated app.
- **Where:** proof-path fixture + a live H2 app.
- **Definition of Done:** delete-cascade, replace-cascade, and the sweep all verified live; no orphan
  after each operation.
- **Verify:** live evidence in `NPDev_General__OutsideRepo`.

---

## 5. Sequencing & dependencies

```
HARDEN-DL       P1 ─► P2 ─► P3                 (independent · ship first · security)
HARDEN-OBJSTORE P1 ─► P2 ─► P4
                  └► P3 (MinIO) ─┘             (independent of DL and GC)
HARDEN-GC       P1 ─► P2
                  └► P3 ─► P4                  (works against inproc; store-agnostic)
```

- **HARDEN-DL is independent and highest value-per-day** — it's the only *security* item; do it first.
- **HARDEN-OBJSTORE** and **HARDEN-GC** are independent of each other and of DL. GC is written against
  `FileStoreContract` so it works for both the inproc and object-store adapters automatically — no need
  to wait for OBJSTORE.
- **Prod-pilot gate** = HARDEN-DL (all) + HARDEN-OBJSTORE P1–P2. GC can fast-follow.

---

## 6. Risk register

| Risk | Feature/Phase | Mitigation |
|---|---|---|
| First external SDK in a kernel adapter bloats/couples the build | OBJSTORE-P1 | Isolate the S3 SDK to the `file-store-objectstore` module only; kernel core and other adapters stay dependency-free |
| Large-file OOM on put/get | OBJSTORE-P1 | S3 multipart-upload + streamed get; assert with a >5MB Testcontainers case |
| No Docker in CI for MinIO | OBJSTORE-P3 | Tag the integration test to skip cleanly without Docker; keep a mocked-client unit test as the always-on gate |
| Delete-cascade aborts the record delete on a store error | GC-P1 | Store-delete failures log + continue; the P3 sweep reclaims stragglers |
| Replace-cascade orphans the new file on a failed save | GC-P2 | Delete the OLD handle only after a confirmed successful save; never before |
| Orphan-sweep reaps a referenced file (data loss) | GC-P3 | Grace window + a positive "referenced?" check before delete; prefer conservative over-retention to any false delete |
| Removing the download content-type param breaks existing links | DL-P1 | Resolve type from the stored handle; keep the endpoint URL/params backward-tolerant (ignore, don't 400, a stale content-type param) |
| Runtime-host code has no standalone build | all runtime-host phases | Verify via the packaged-app generate→compile→boot proof path, per the LIFT-UPLOAD precedent |

---

## 7. Immediate next actions

1. **HARDEN-DL-P1+P2** — the security fix, ~1 day, no new infra. Serve the stored content-type,
   force `attachment` + `nosniff` + CSP on `/api/files/**`. Ship this first regardless of the rest.
2. **HARDEN-OBJSTORE-P1** — scaffold `file-store-objectstore` against the AWS SDK v2 S3 client;
   prove it with a mocked client, then MinIO (P3).
3. **HARDEN-GC-P1** — delete-cascade; the highest-value orphan fix, and it lands against the existing
   inproc adapter with no dependency on OBJSTORE.

---

## 8. Changelog

- **2026-07-13** — Initial roadmap. Closes the two gaps left after the LIFT-* work (`a63ba7f`):
  download stored-XSS (HARDEN-DL) and ARCH-upload dev-only (HARDEN-OBJSTORE object store + HARDEN-GC
  file lifecycle). Total effort ≈ 7–12 ideal-days; DL is the ~1-day security priority.
