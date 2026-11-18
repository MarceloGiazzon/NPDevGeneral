// Archetype "form": input for a flow. Controls come from fieldMeta types; the primary action starts the flow with the
// typed values. Required/format problems show next to the field, as do the field errors of the server's 400/422 body.
import { h, humanize, inputKind, selectFields, validateForm } from "./core.js";
import { renderActions } from "./actions.js";
import { pickMap } from "./map.js";

function control(field, meta) {
  const kind = inputKind(meta);
  const props = { id: "f-" + field.value, name: field.value, class: "control", required: meta.required && kind.type !== "checkbox" };
  if (kind.control === "select") {
    return h("select", props, h("option", { value: "", text: "Choose..." }),
      kind.options.map((o) => h("option", { value: typeof o === "object" ? o.value : o, text: typeof o === "object" ? (o.label || o.value) : o })));
  }
  if (kind.control === "textarea") return h("textarea", Object.assign(props, { rows: 3 }));
  return h("input", Object.assign(props, { type: kind.type, step: kind.step, inputmode: kind.inputmode, autocomplete: "off" }));
}

const rawValue = (el) => (el.type === "checkbox" ? el.checked : el.value);

export function render(ctx) {
  const { screen, root } = ctx;
  const fields = selectFields(screen);
  const controls = {};
  const notes = {};
  const form = h("form", { class: "form panel", novalidate: true, onsubmit: (e) => e.preventDefault() });
  for (const field of fields) {
    const meta = (screen.fieldMeta || {})[field.value] || {};
    controls[field.value] = control(field, meta);
    notes[field.value] = h("span", { class: "field-error", role: "alert" });
    const label = h("label", { class: meta.type === "boolean" ? "form-field inline" : "form-field", for: "f-" + field.value },
      h("span", { class: "k", text: (field.label || humanize(field.value)) + (meta.required ? " *" : "") }), controls[field.value], notes[field.value]);
    form.appendChild(label);
  }
  // map.pickPoints: tap/drag on a map fills the lat/lng fields above (they stay editable inputs); Leaflet loads only now
  if (screen.map && Array.isArray(screen.map.pickPoints) && screen.map.pickPoints.length) form.appendChild(pickMap(ctx, screen.map, controls).el);
  const setErrors = (errors) => {
    for (const name of Object.keys(notes)) notes[name].textContent = errors[name] || "";
    const first = Object.keys(errors).find((n) => controls[n]);
    if (first) controls[first].focus();
  };
  let current = { values: {}, errors: {} };
  const check = () => {
    const raw = {};
    for (const name of Object.keys(controls)) raw[name] = rawValue(controls[name]);
    current = validateForm(fields, raw, screen.fieldMeta);
    setErrors(current.errors);
    return Object.keys(current.errors).length === 0;
  };
  const actions = renderActions(ctx, screen.actions, {
    inForm: true, scope: () => ({ form: current.values }), beforeRun: check,
    onFieldErrors: (list) => setErrors(Object.fromEntries(list.map((e) => [e.field, e.message])))
  });
  form.appendChild(actions);
  root.appendChild(form);
}
