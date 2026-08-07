# Editor e2e against a real generated app

`editor/ANALYSIS.md` E3: the editor's real home is `/npdev-ui-react/` inside a generated
FinalApp -- a different base path, real auth, and real REST data than
`playwright.config.ts`'s default `npm run e2e`, which serves the built bundle from a static
file host with no Spring Boot backend behind it at all.

This spec targets an already-running real app instead of booting one itself (generating +
building a FinalApp is a multi-minute Gradle build, not something a `playwright test` process
should own).

## Run it

1. Generate + build + boot a FinalApp (any app works; the fastest is the T1 canary):

   ```powershell
   pwsh -NoProfile -File scripts/appgen/Generate-CanarySample.ps1 -SampleId npdev-canary
   cd NPDevSamples/npdev-canary/Output/App
   ./gradlew bootJar
   java -jar build/libs/*.jar --spring.profiles.active=dev --server.port=8080
   ```

2. In another shell, from `NPDevEditor/ui-react`:

   ```bash
   npm ci
   npx playwright install --with-deps chromium   # first run only
   PLAYWRIGHT_GENERATED_APP_URL=http://localhost:8080 npm run e2e:generated-app
   ```

## What it proves

- The workbench shell renders at the real `/npdev-ui-react/` path (not `/`).
- Clicking into the Prompt History tab makes a genuine network round-trip to
  `/api/admin/model/semantic-behavior-writeback/history` on the running app (proving the editor's
  own dev-mode `X-Api-Key` auth works against a real backend) and the panel settles into either
  real data or its own graceful degraded-warning state -- never a silent infinite spinner or an
  uncaught exception (checked on every test via a `pageerror` listener).
- The authoring surface boots under the same real base path/auth.

Not proven here: a 200 from semantic-behavior-writeback's endpoints. REG-138 records that the
default `supported-core` build profile excludes that controller entirely, so a 404 there is
currently the EXPECTED result -- this spec deliberately does not assert on that response's status,
only that the panel handles whatever it gets without crashing.
