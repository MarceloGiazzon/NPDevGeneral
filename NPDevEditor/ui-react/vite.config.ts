import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const uiRoot = fileURLToPath(new URL(".", import.meta.url));
const workspaceRoot = path.resolve(uiRoot, "..", "..");
const sourceRoot = path.resolve(uiRoot, "src");
const boundaryPath = path.resolve(uiRoot, "ui-boundary.json");
const npdevBuildRoot = process.env.NPDEV_BUILD_ROOT
  ? path.resolve(process.env.NPDEV_BUILD_ROOT)
  : path.resolve(workspaceRoot, "..", "Build");
const uiDistDir = process.env.NPDEV_UI_DIST_DIR
  ? path.resolve(process.env.NPDEV_UI_DIST_DIR)
  : path.resolve(npdevBuildRoot, "ui", "npdev-editor-ui-react", "dist");

type UiBoundaryStatus = "allowed" | "deferred" | "test-only";

function toBoundaryPath(absolutePath: string) {
  return path.relative(workspaceRoot, absolutePath).split(path.sep).join("\\");
}

function collectComponentFiles(directory: string): string[] {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      return collectComponentFiles(entryPath);
    }
    if (entry.isFile() && entry.name.endsWith(".tsx")) {
      return [toBoundaryPath(entryPath)];
    }
    return [];
  });
}

function enforceUiBoundary() {
  const boundary = JSON.parse(fs.readFileSync(boundaryPath, "utf8")) as {
    surfaceClassifications?: Partial<Record<UiBoundaryStatus, string[]>>;
  };
  const classifications = boundary.surfaceClassifications;
  if (!classifications) {
    throw new Error("ui-boundary.json must declare surfaceClassifications.");
  }

  const discovered = new Set(collectComponentFiles(sourceRoot));
  const owners = new Map<string, UiBoundaryStatus>();
  for (const status of ["allowed", "deferred", "test-only"] as UiBoundaryStatus[]) {
    for (const filePath of classifications[status] ?? []) {
      if (owners.has(filePath)) {
        throw new Error(`UI boundary surface is classified more than once: ${filePath}`);
      }
      owners.set(filePath, status);
    }
  }

  const missing = [...discovered].filter((filePath) => !owners.has(filePath)).sort();
  const stale = [...owners.keys()].filter((filePath) => !discovered.has(filePath)).sort();
  if (missing.length > 0 || stale.length > 0) {
    throw new Error([
      "UI boundary classification failed.",
      missing.length > 0 ? `Unlisted component surfaces: ${missing.join(", ")}` : "",
      stale.length > 0 ? `Stale classified component surfaces: ${stale.join(", ")}` : ""
    ].filter(Boolean).join(" "));
  }
}

export default defineConfig({
  base: "/npdev-ui-react/",
  cacheDir: path.resolve(npdevBuildRoot, "vite", "npdev-editor-ui-react"),
  resolve: {
    alias: {
      "@npdev-samples": path.resolve(workspaceRoot, "NPDevSamples")
    }
  },
  plugins: [
    {
      name: "npdev-ui-boundary-lock",
      buildStart() {
        enforceUiBoundary();
      }
    },
    react()
  ],
  server: {
    fs: {
      allow: [workspaceRoot]
    }
  },
  build: {
    sourcemap: false,
    outDir: uiDistDir,
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "assets/app.js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: (assetInfo) => {
          if ((assetInfo.name ?? "").endsWith(".css")) {
            return "assets/app.css";
          }
          return "assets/[name][extname]";
        }
      }
    }
  }
});
