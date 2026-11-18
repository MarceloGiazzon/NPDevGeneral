// Archetype "status": "my current thing" -- the latest row of the filtered set, with the actions it allows.
// Live-ready: app.js re-renders a `live` screen on a timer today (polling); the server-sent events of P5-T5 only
// have to call the same reload.
import { render as renderDetail } from "./detail.js";

export const render = (ctx) => renderDetail(ctx, "latest");
