import { createReadStream } from "node:fs";
import { access, readFile } from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "..");
const hostRoot = path.join(projectRoot, "playwright-static");
const appRoot = path.join(hostRoot, "npdev-ui-react");
const port = Number(process.argv[2] ?? process.env.PORT ?? "5173");

const mimeTypes = new Map([
  [".html", "text/html; charset=utf-8"],
  [".js", "application/javascript; charset=utf-8"],
  [".css", "text/css; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".svg", "image/svg+xml"],
  [".png", "image/png"],
  [".jpg", "image/jpeg"],
  [".jpeg", "image/jpeg"],
  [".webm", "video/webm"]
]);

function contentTypeFor(filePath) {
  return mimeTypes.get(path.extname(filePath).toLowerCase()) ?? "application/octet-stream";
}

async function exists(filePath) {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
}

function safeJoin(rootDir, requestPath) {
  const candidate = path.normalize(path.join(rootDir, requestPath));
  if (!candidate.startsWith(rootDir)) {
    return null;
  }
  return candidate;
}

async function sendFile(response, filePath) {
  response.writeHead(200, { "Content-Type": contentTypeFor(filePath) });
  createReadStream(filePath).pipe(response);
}

async function sendText(response, statusCode, message) {
  response.writeHead(statusCode, { "Content-Type": "text/plain; charset=utf-8" });
  response.end(message);
}

const server = http.createServer(async (request, response) => {
  const requestUrl = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  const pathname = decodeURIComponent(requestUrl.pathname);

  try {
    if (pathname === "/" || pathname === "") {
      await sendFile(response, path.join(hostRoot, "index.html"));
      return;
    }

    const trimmedPath = pathname.replace(/^\/+/, "");
    const resolvedPath = safeJoin(hostRoot, trimmedPath);
    if (resolvedPath && (await exists(resolvedPath))) {
      const filePath = (await exists(path.join(resolvedPath, "index.html"))) ? path.join(resolvedPath, "index.html") : resolvedPath;
      await sendFile(response, filePath);
      return;
    }

    if (pathname.startsWith("/npdev-ui-react")) {
      await sendFile(response, path.join(appRoot, "index.html"));
      return;
    }

    await sendText(response, 404, `Not found: ${pathname}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await sendText(response, 500, message);
  }
});

server.listen(port, "127.0.0.1", async () => {
  const rootIndex = await readFile(path.join(hostRoot, "index.html"), "utf8");
  if (!rootIndex.includes("/npdev-ui-react/")) {
    console.warn("Playwright static host root index is missing the /npdev-ui-react/ redirect.");
  }
  console.log(`Playwright static host listening at http://127.0.0.1:${port}`);
});
