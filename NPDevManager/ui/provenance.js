// The Impact tab -- S15 (NPDEV_MEGA_ROADMAP.md, Track B): the interactive view over S14's
// provenance-index.json. Thin pipe, same standing rule as the Verification tab: what maps to what
// was decided by the GENERATOR (ProvenanceIndexEmitter) -- this window only renders the index, it
// never re-derives an artifact mapping or a digest.

const { invoke: provInvoke } = window.__TAURI__.core;

const provState = {
  apps: [],
  index: null,
  selectedNode: null,
};

function provEsc(value) {
  const div = document.createElement("div");
  div.textContent = value == null ? "" : String(value);
  return div.innerHTML;
}

function shortDigest(digest) {
  if (!digest) return "—";
  const bare = digest.startsWith("sha256:") ? digest.slice(7) : digest;
  return bare.length > 12 ? `${bare.slice(0, 12)}…` : bare;
}

function artifactTypeLabel(type) {
  if (type === "generated-class") return "generated class";
  if (type === "schema-migration") return "schema migration";
  if (type === "frontend-route") return "frontend route";
  return type;
}

function artifactBadge(type) {
  if (type === "schema-migration") return `<span class="pbadge local">schema</span>`;
  if (type === "frontend-route") return `<span class="pbadge verified">route</span>`;
  return `<span class="pbadge unsigned">java</span>`;
}

function renderNodes() {
  const box = document.getElementById("provenance-nodes");
  const index = provState.index;
  if (!index) {
    box.innerHTML = `<p class="status-line">Pick an app to load its provenance index.</p>`;
    document.getElementById("provenance-detail").innerHTML =
      `<p class="status-line">Select a concept node to see what it produced.</p>`;
    return;
  }
  const nodes = index.specNodes || {};
  const names = Object.keys(nodes).sort();
  if (names.length === 0) {
    box.innerHTML = `<p class="status-line">This app's provenance index has no spec nodes.</p>`;
    return;
  }
  box.innerHTML = names.map((name) => {
    const node = nodes[name];
    const count = (node.artifacts || []).length;
    const selected = name === provState.selectedNode;
    return `
      <div class="prow${selected ? " selected" : ""}" data-node="${provEsc(name)}" title="table ${provEsc(node.table || "")}">
        <div class="prow-main">
          <span class="pname">${provEsc(name)}</span>
          <span class="pdigest">${count} artifact${count === 1 ? "" : "s"}</span>
        </div>
      </div>`;
  }).join("");
  box.querySelectorAll("[data-node]").forEach((row) =>
    row.addEventListener("click", () => selectNode(row.dataset.node)));
}

function renderDetail() {
  const box = document.getElementById("provenance-detail");
  const index = provState.index;
  const name = provState.selectedNode;
  if (!index || !name) {
    box.innerHTML = `<p class="status-line">Select a concept node to see what it produced.</p>`;
    return;
  }
  const node = (index.specNodes || {})[name];
  if (!node) {
    box.innerHTML = `<p class="status-line err">Unknown node: ${provEsc(name)}</p>`;
    return;
  }
  const artifacts = node.artifacts || [];
  if (artifacts.length === 0) {
    box.innerHTML = `
      <h4>${provEsc(name)}</h4>
      <p class="status-line">No artifacts were recorded for this node (the index only records
      what generation actually emitted — nothing was fabricated).</p>`;
    return;
  }
  box.innerHTML = `
    <h4>${provEsc(name)}</h4>
    <p class="subtitle">table <code>${provEsc(node.table || "—")}</code></p>
    ${artifacts.map((a) => `
      <div class="prov-artifact" data-path="${provEsc(a.path || "")}">
        <div class="prow-main">
          ${artifactBadge(a.type)}
          <span class="pdigest" title="full ${provEsc(a.digest || "")}">${shortDigest(a.digest)}</span>
        </div>
        <div class="prov-path">${provEsc(a.type === "frontend-route" ? (a.note || a.path) : a.path)}</div>
        ${a.note && a.type !== "frontend-route" ? `<div class="prov-note">${provEsc(a.note)}</div>` : ""}
      </div>`).join("")}
    <p class="subtitle" style="margin-top:12px">Click any artifact to jump back to its origin —
      this is the reverse edge of the same index.</p>`;
  box.querySelectorAll("[data-path]").forEach((el) =>
    el.addEventListener("click", () => selectArtifact(el.dataset.path)));
}

// Clicking an artifact highlights its source: reveal which spec node produced it. For the three
// artifact families S14 covers this is the same node (each artifact is already shown under its
// node), so the interaction is a re-focus -- the detail pane scrolls the node's own row into view.
function selectArtifact(path) {
  const nodes = document.getElementById("provenance-nodes");
  const rows = nodes.querySelectorAll("[data-node]");
  rows.forEach((row) => row.classList.remove("flash"));
  // Find the node that produced this path and bring it into view.
  const index = provState.index;
  let producer = null;
  for (const [name, node] of Object.entries(index.specNodes || {})) {
    if ((node.artifacts || []).some((a) => a.path === path)) {
      producer = name;
      break;
    }
  }
  if (producer) {
    for (const row of rows) {
      if (row.dataset.node === producer) {
        row.classList.add("flash");
        row.scrollIntoView({ block: "nearest" });
        setTimeout(() => row.classList.remove("flash"), 900);
        break;
      }
    }
  }
}

function selectNode(name) {
  provState.selectedNode = name;
  renderNodes();
  renderDetail();
}

function provStatus(message, bad) {
  const el = document.getElementById("provenance-status");
  el.textContent = message || "";
  el.style.color = bad ? "var(--err)" : "var(--muted)";
}

function currentAppDir() {
  const picker = document.getElementById("provenance-app-picker");
  return picker && picker.value ? picker.value : null;
}

async function refreshAppPicker() {
  const label = document.getElementById("provenance-app-label");
  const picker = document.getElementById("provenance-app-picker");
  let apps = [];
  try {
    apps = await provInvoke("list_apps");
  } catch {
    apps = [];
  }
  provState.apps = apps;
  if (apps.length === 0) {
    label.hidden = true;
    picker.innerHTML = "";
    return;
  }
  const previous = picker.value;
  label.hidden = false;
  picker.innerHTML = apps.map((a) => `<option value="${provEsc(a.directory)}">${provEsc(a.name)}</option>`).join("");
  if (previous && apps.some((a) => a.directory === previous)) picker.value = previous;
  else picker.value = apps[apps.length - 1].directory;
}

async function refreshProvenance() {
  const appDir = currentAppDir();
  provState.selectedNode = null;
  if (!appDir) {
    provState.index = null;
    renderNodes();
    return;
  }
  provStatus("reading provenance-index.json…", false);
  try {
    provState.index = await provInvoke("app_provenance_index", { appDir });
    provStatus(`index v${provState.index.schemaVersion || "?"} — ${(provState.index.nodeCount ?? 0)} spec node(s)`, false);
  } catch (error) {
    provState.index = null;
    provStatus(`no provenance index: ${error}`, true);
  }
  renderNodes();
}

function initProvenance() {
  document.getElementById("provenance-refresh").addEventListener("click", async () => {
    await refreshAppPicker();
    await refreshProvenance();
  });
  document.getElementById("provenance-app-picker").addEventListener("change", refreshProvenance);
  refreshAppPicker().then(refreshProvenance);
}

window.__npdevInitProvenance = initProvenance;
window.__npdevRefreshProvenance = refreshProvenance;