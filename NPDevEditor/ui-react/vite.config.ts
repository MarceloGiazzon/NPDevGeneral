import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const uiRoot = fileURLToPath(new URL(".", import.meta.url));
const workspaceRoot = path.resolve(uiRoot, "..", "..");

export default defineConfig({
  base: "/npdev-ui-react/",
  resolve: {
    alias: {
      "@npdev-samples": path.resolve(workspaceRoot, "NPDevSamples")
    }
  },
  plugins: [react()],
  server: {
    fs: {
      allow: [workspaceRoot]
    }
  },
  build: {
    sourcemap: false,
    outDir: "dist",
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
