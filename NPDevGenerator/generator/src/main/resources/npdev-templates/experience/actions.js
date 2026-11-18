// Shared action buttons. A button is exactly one thing: POST the action's startUrl (a flow the server authorises)
// with a body built from the action's `input` expressions. `confirm` adds an in-page confirmation step, `style`
// picks primary|secondary|danger, `visibleWhen` hides the button, and on success the screen follows `then`
// (a screen name) or refreshes.
import { buildInput, card, fieldErrors, h, visibleActions } from "./core.js";

/**
 * ctx  = {api, user, go(hash), reload(), toast(text), reportError(message), screen}
 * opts = {scope: () => ({row, form}), beforeRun?: () => boolean, onFieldErrors?: (list) => void, inForm?: boolean}
 */
export function renderActions(ctx, actions, opts) {
  const o = opts || {};
  const scope = () => Object.assign({ user: ctx.user }, o.scope ? o.scope() : {});
  const shown = visibleActions(actions, scope());
  const box = h("div", { class: "actions" });
  box.shown = shown;
  box.lock = () => {};
  box.activate = () => {};
  if (!shown.length) return box;
  const buttons = [];
  let locked = false;

  const showButtons = () => { box.replaceChildren(...buttons); };

  // lock(): the row can no longer be acted on (an expired offer). Buttons stay disabled for good; never runs a flow.
  box.lock = () => { locked = true; buttons.forEach((b) => { b.disabled = true; }); showButtons(); };
  // activate(action): exactly what a click on that action's button does (confirm step included) -- used by drag and drop.
  box.activate = (action) => { if (!locked && shown.includes(action)) return action.confirm ? askConfirm(action) : run(action); };

  async function run(action) {
    if (locked) return;
    buttons.forEach((b) => { b.disabled = true; });
    try {
      const result = await ctx.api.post(action.startUrl, buildInput(action, scope()));
      ctx.toast("Done.");
      await ctx.afterAction(action, result);
    } catch (error) {
      buttons.forEach((b) => { b.disabled = locked; });
      showButtons();
      const list = fieldErrors(error && error.payload, (ctx.screen && ctx.screen.fieldMeta) ? Object.keys(ctx.screen.fieldMeta) : []);
      if (o.onFieldErrors && list.length) o.onFieldErrors(list);
      ctx.reportError((error && error.message) || "That did not work.");
    }
  }

  function askConfirm(action) {
    const yes = h("button", { type: "button", class: "btn " + (action.style === "danger" ? "danger" : "primary"), text: "Confirm", onclick: () => run(action) });
    const no = h("button", { type: "button", class: "btn secondary", text: "Back", onclick: showButtons });
    box.replaceChildren(card("notice", null, action.confirm), h("div", { class: "actions" }, yes, no));
    yes.focus();
  }

  for (const action of shown) {
    const style = ["primary", "secondary", "danger"].includes(action.style) ? action.style : "secondary";
    buttons.push(h("button", {
      class: "btn " + style, text: action.label || action.flow,
      type: o.inForm && style === "primary" ? "submit" : "button",
      onclick: (event) => {
        if (event && event.preventDefault) event.preventDefault();
        if (locked) return;
        if (o.beforeRun && !o.beforeRun()) return;
        return action.confirm ? askConfirm(action) : run(action);
      }
    }));
  }
  showButtons();
  return box;
}
