import { cp, mkdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "..");
const distDir = path.join(projectRoot, "dist");
const hostRoot = path.join(projectRoot, "playwright-static");
const appRoot = path.join(hostRoot, "npdev-ui-react");

await rm(hostRoot, { recursive: true, force: true });
await mkdir(appRoot, { recursive: true });
await cp(distDir, appRoot, { recursive: true });
await writeFile(
  path.join(hostRoot, "index.html"),
  [
    "<!doctype html>",
    '<html lang="en">',
    "  <head>",
    '    <meta charset="UTF-8" />',
    '    <meta http-equiv="refresh" content="0; url=/npdev-ui-react/" />',
    "    <title>NPDev UI React E2E Host</title>",
    "  </head>",
    "  <body></body>",
    "</html>"
  ].join("\n"),
  "utf8"
);

console.log(`Prepared Playwright static host at ${hostRoot}`);
