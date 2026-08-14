import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const uiRoot = fileURLToPath(new URL(".", import.meta.url));
const workspaceRoot = path.resolve(uiRoot, "..", "..");

export default defineConfig({
  resolve: {
    alias: {
      "@npdev-samples": path.resolve(workspaceRoot, "NPDevSamples"),
      // R6 (MASTER-ROADMAP.md): lets the schema-driven loss-detector test enumerate the real
      // model.schema.json root keys instead of hand-maintaining a second, driftable list.
      "@npdev-schema": path.resolve(workspaceRoot, "NPDevContract", "schemas", "model.schema.json")
    }
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
    coverage: {
      provider: "v8",
      // R3 extension (Track C C8 / ledger QUAL-6): run-frontend-gate.ps1's Find-GeneratedResidue
      // (and NPDevEditor/build.gradle's own cleanUiReactGenerated) both hard-fail the moment a
      // "coverage" directory shows up inside ui-react -- see ROADMAP.md card R3's own warning.
      // Writing the report to scripts/reports/out/ (already .gitignore'd, already the stable,
      // non-cleaned path check-coverage-ratchet.py's siblings read from) keeps coverage output
      // entirely outside ui-react instead.
      reportsDirectory: path.resolve(workspaceRoot, "scripts", "reports", "out", "vitest-coverage"),
      reporter: ["text", "json-summary"],
      // `all: true` + explicit `include` makes untouched src files count as 0%-covered in the
      // denominator (mirroring the Gradle jacoco plugin's default classDirectories behaviour,
      // which reports every compiled class, not just the ones a test happened to load) so the
      // ratchet floor reflects the whole authoring surface, not just the ~13 files under test today.
      all: true,
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.ts", "src/**/*.d.ts"]
    }
  }
});
