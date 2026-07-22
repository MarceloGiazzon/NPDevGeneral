import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const uiRoot = resolve(__dirname, "..");
const workspaceRoot = resolve(uiRoot, "..", "..");
const npdevBuildRoot = process.env.NPDEV_BUILD_ROOT
  ? resolve(process.env.NPDEV_BUILD_ROOT)
  : resolve(workspaceRoot, "..", "Build");
const distDir = process.env.NPDEV_UI_DIST_DIR
  ? resolve(process.env.NPDEV_UI_DIST_DIR)
  : resolve(npdevBuildRoot, "ui", "npdev-editor-ui-react", "dist");
const targetDir = resolve(
  uiRoot,
  "..",
  "..",
  "NPDevGenerator",
  "generator",
  "src",
  "main",
  "resources",
  "npdev-templates",
  "static-react"
);

if (!existsSync(distDir)) {
  throw new Error(`React dist directory not found: ${distDir}. Run "npm run build" first.`);
}

rmSync(targetDir, { recursive: true, force: true });
mkdirSync(targetDir, { recursive: true });
cpSync(distDir, targetDir, { recursive: true });

console.log(`React dist exported to generator templates: ${targetDir}`);
