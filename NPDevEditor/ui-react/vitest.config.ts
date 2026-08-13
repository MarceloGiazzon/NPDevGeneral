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
    include: ["src/**/*.test.ts"]
  }
});
