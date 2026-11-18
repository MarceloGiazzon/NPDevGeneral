// Reads the server-sent-events stream GET /api/live with fetch() and a ReadableStream, not EventSource: an EventSource
// cannot send the Authorization header this app authenticates with. The stream only says "row changed"; the caller
// refetches through the normal REST API. Everything here is plain data in, callbacks out (live.js owns the timers).
export const MAX_LINE = 16384;

/** Incremental SSE parser. push(text) any number of times (chunks may split lines, CRLF pairs and events anywhere);
 *  onEvent({event, data}) once per dispatched event. Comment lines and unknown fields are ignored and an oversized
 *  line is dropped, so a hostile or broken stream cannot grow the buffer. */
export function createSseParser(onEvent) {
  let buf = "";
  let event = "";
  let data = [];
  let dropping = false;
  const dispatch = () => {
    if (data.length) { try { onEvent({ event: event || "message", data: data.join("\n") }); } catch (e) { /* a bad handler must not stop the parser */ } }
    event = ""; data = [];
  };
  const line = (l) => {
    if (l === "") { dispatch(); return; }
    if (l.charAt(0) === ":") return;
    const i = l.indexOf(":");
    const field = i < 0 ? l : l.slice(0, i);
    let value = i < 0 ? "" : l.slice(i + 1);
    if (value.charAt(0) === " ") value = value.slice(1);
    if (field === "event") event = value;
    else if (field === "data" && data.length < 64) data.push(value);
  };
  return {
    push(text) {
      buf += text;
      for (;;) {
        const m = /\r\n|\r|\n/.exec(buf);
        if (!m || (m[0] === "\r" && m.index + 1 === buf.length)) break; // a lone CR at the very end may be half of a CRLF
        const l = buf.slice(0, m.index);
        buf = buf.slice(m.index + m[0].length);
        if (dropping) dropping = false; else line(l);
      }
      if (buf.length > MAX_LINE) { buf = ""; dropping = true; }
    }
  };
}

/** Open the stream once. o = {fetch, url, headers, onOpen(), onEvent({event,data}), onClose({opened, retry})}.
 *  onClose is called exactly once unless close() was called first. retry is true for network errors, 429, 5xx and a
 *  stream that ended; false for any other refusal (400/401/403/404: the caller stays on polling, which has its own
 *  401 handling). Returns {close()}. */
export function openStream(o) {
  const ctl = typeof AbortController === "function" ? new AbortController() : null;
  let closed = false;
  let reader = null;
  (async () => {
    let res;
    try { res = await o.fetch(o.url, { headers: o.headers, signal: ctl ? ctl.signal : undefined, cache: "no-store" }); }
    catch (e) { if (!closed) o.onClose({ opened: false, retry: true }); return; }
    if (closed) return;
    if (!res || !res.ok || !res.body || typeof res.body.getReader !== "function") {
      o.onClose({ opened: false, retry: !!res && (res.status === 429 || res.status >= 500) });
      return;
    }
    reader = res.body.getReader();
    const decoder = new TextDecoder();
    const parser = createSseParser((ev) => { if (!closed) o.onEvent(ev); });
    o.onOpen();
    try {
      for (;;) {
        const r = await reader.read();
        if (r.done || closed) break;
        parser.push(decoder.decode(r.value, { stream: true }));
      }
    } catch (e) { /* aborted or the connection dropped */ }
    if (!closed) o.onClose({ opened: true, retry: true });
  })();
  return {
    close() {
      closed = true;
      try { if (reader) reader.cancel(); } catch (e) { /* already closed */ }
      try { if (ctl) ctl.abort(); } catch (e) { /* already closed */ }
    }
  };
}
