export const DEFAULT_DEV_API_KEY = "api-dev";

const API_KEY_STORAGE_KEY = "npdev.ui.apiKey";

function readStoredApiKey(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    const stored = window.localStorage.getItem(API_KEY_STORAGE_KEY);
    return stored && stored.trim() ? stored.trim() : null;
  } catch {
    return null;
  }
}

function storeDefaultApiKey(): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(API_KEY_STORAGE_KEY, DEFAULT_DEV_API_KEY);
  } catch {
    // Storage can be disabled in hardened browsers; the header can still be supplied in memory.
  }
}

export function getApiKey(): string {
  const stored = readStoredApiKey();
  if (stored) {
    return stored;
  }
  storeDefaultApiKey();
  return DEFAULT_DEV_API_KEY;
}

export function withApiKeyHeaders(headers?: HeadersInit): Headers {
  const next = new Headers(headers);
  if (!next.has("X-Api-Key")) {
    next.set("X-Api-Key", getApiKey());
  }
  return next;
}

function isSameOriginApiRequest(input: RequestInfo | URL): boolean {
  if (typeof window === "undefined") {
    return false;
  }

  const rawUrl = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  if (rawUrl.startsWith("/api/") || rawUrl === "/api") {
    return true;
  }

  try {
    const url = new URL(rawUrl, window.location.href);
    return url.origin === window.location.origin && (url.pathname.startsWith("/api/") || url.pathname === "/api");
  } catch {
    return false;
  }
}

type ApiKeyFetchWindow = Window & {
  __npdevApiKeyFetchInstalled?: boolean;
};

export function installDefaultApiKeyFetch(): void {
  if (typeof window === "undefined" || typeof window.fetch !== "function") {
    return;
  }

  const typedWindow = window as ApiKeyFetchWindow;
  if (typedWindow.__npdevApiKeyFetchInstalled) {
    return;
  }

  const originalFetch = window.fetch.bind(window);
  typedWindow.__npdevApiKeyFetchInstalled = true;
  window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (!isSameOriginApiRequest(input)) {
      return originalFetch(input, init);
    }

    const request = input instanceof Request ? input : undefined;
    const headers = withApiKeyHeaders(init?.headers ?? request?.headers);
    if (request) {
      return originalFetch(new Request(request, { ...init, headers }));
    }
    return originalFetch(input, { ...init, headers });
  };
}
