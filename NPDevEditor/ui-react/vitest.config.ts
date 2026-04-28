import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const uiRoot = fileURLToPath(new URL(".", import.meta.url));
const workspaceRoot = path.resolve(uiRoot, "..", "..");

export default defineConfig({
  resolve: {
    alias: {
      "@npdev-samples": path.resolve(workspaceRoot, "NPDevSamples")
    }
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"]
  }
});
