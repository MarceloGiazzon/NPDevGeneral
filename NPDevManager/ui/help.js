// The "?" help popups.
//
// Screens like Share and Versions are full of words a non-specialist has never needed (tunnel,
// ingress, rung, tag). Each "?" opens one short plain-language answer -- what it is, why you would
// want it, what to do -- plus a link to the full document for anyone who wants to go deeper.
//
// Use: put `helpButton("key")` (or `<button class="help-btn" data-help="key">`) next to a heading.
// One delegated listener below handles every button, including ones rendered later by other files.

const { invoke: helpInvoke } = window.__TAURI__.core;

const DOCS = "https://github.com/MarceloGiazzon/NPDevGeneral/blob/main/docs";

const HELP = {
  // ---------------------------------------------------------------------------------- Share
  "share-overview": {
    title: "What is the Share tab?",
    body: `
      <p>Your app runs on <strong>this computer</strong>. Right now only you can open it. This tab
      gives it an address that <strong>other people</strong> can open too.</p>
      <p>There are five levels, from "nobody but me" to "a real server on the internet". Pick the
      lowest one that does what you need. Every level is a fine place to stop.</p>
      <ol>
        <li><strong>0 · Private</strong>: only you. The default and the safest.</li>
        <li><strong>1 · Shared by link</strong>: send someone a link for a quick demo. It only works
        while this computer is on, and the link changes every time.</li>
        <li><strong>2 · Permanent address, your own box</strong>: a fixed web address that points to
        this computer.</li>
        <li><strong>3 · External free tier</strong>: the app runs on a hosting company's free plan,
        so it works even when your computer is off. It usually sleeps when idle.</li>
        <li><strong>4 · External paid host</strong>: a server you rent. Always on, no sleeping.</li>
      </ol>
      <p>Before anything is shared, NPDev <strong>checks the app</strong> and fixes what it can by
      itself, so you never expose an app with, say, no login.</p>`,
    links: [{ label: "Hosting guide (full details)", url: `${DOCS}/HOSTING.md` }],
  },
  "rung-0": {
    title: "0 · Private",
    body: `<p>Only this computer can open the app (at <code>localhost</code>). Nothing is exposed to
      the network. This is where every app starts, and you can come back here at any time with
      "Stop sharing".</p>`,
  },
  "rung-1": {
    title: "1 · Shared by link (a tunnel)",
    body: `
      <p>A <strong>tunnel</strong> is a small program (cloudflared or ngrok) that gives this
      computer a temporary public web address, like <code>https://random-words.trycloudflare.com</code>.
      Anyone with the link can open your app.</p>
      <p><strong>Good for:</strong> showing the app to someone for a few minutes or hours.</p>
      <p><strong>Keep in mind:</strong> it works only while this computer is on and the tunnel is
      open, and the address changes each time you open a new one. The tunnel program must be
      installed; the card says so if it is not.</p>`,
    links: [
      { label: "cloudflared (Cloudflare Tunnel)", url: "https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/" },
      { label: "ngrok", url: "https://ngrok.com/docs" },
      { label: "Hosting guide", url: `${DOCS}/HOSTING.md` },
    ],
  },
  "rung-2": {
    title: "2 · Permanent address, your own box",
    body: `<p>A web address you own (a domain, like <code>app.example.com</code>) pointed at this
      computer. Unlike a tunnel link it does not change. It still needs this computer to be on, and
      usually needs your router or DNS set up to point at it.</p>`,
    links: [{ label: "Hosting guide", url: `${DOCS}/HOSTING.md` }],
  },
  "rung-3": {
    title: "3 · External free tier",
    body: `
      <p>The app is copied to a hosting company (for example Render or Koyeb) and runs on
      <strong>their</strong> computers, so it keeps working when yours is off.</p>
      <p><strong>Keep in mind:</strong> free plans have real limits. The app may <em>sleep</em>
      when nobody uses it (the first visit afterwards is slow), memory is small, and some have no
      permanent disk, so the database must live elsewhere. Each card states its own limits, and some
      require a specific database engine.</p>`,
    links: [{ label: "Hosting guide: deploying with no persistent disk", url: `${DOCS}/HOSTING.md#deploying-with-no-persistent-disk` }],
  },
  "rung-4": {
    title: "4 · External paid host",
    body: `<p>A server you rent (a VPS) running Linux. Always on, no sleeping, no cold starts. You
      give NPDev the server's SSH login, and it ships the built app there and starts it behind HTTPS.
      You are the one who looks after that machine.</p>`,
    links: [{ label: "Hosting guide: deploying to your own server", url: `${DOCS}/HOSTING.md#deploying-to-your-own-server-rung-4-vps` }],
  },
  "share-checks": {
    title: "The checks before sharing",
    body: `
      <p>Before anything is exposed, NPDev asks: <em>what would a real visitor hit right now?</em>
      For example: is a login required, are secrets kept out of the pages, does the health page
      reveal internal details?</p>
      <p><strong>✓</strong> fine · <strong>!</strong> worth knowing, does not block ·
      <strong>✗</strong> must be fixed first. The main button fixes whatever it can by itself
      ("Fix 2 things, then share") and tells you about anything it cannot.</p>`,
    links: [{ label: "Hosting guide: the verbs (check)", url: `${DOCS}/HOSTING.md#the-verbs` }],
  },
  "share-path": {
    title: "How a visitor reaches your app",
    body: `
      <p>The chain from someone's browser to your app:</p>
      <ul>
        <li><strong>Browser</strong>: the visitor opens the link.</li>
        <li><strong>Tunnel</strong>: carries the request to this computer.</li>
        <li><strong>Shared ingress</strong>: one small web server on this computer that receives
        every shared request and passes it to the right app. Because of it, one tunnel can serve
        several apps, each under its own path (<code>/app-name/</code>).</li>
        <li><strong>This machine → Your app</strong>: the app answers.</li>
      </ul>`,
  },
  "share-routed": {
    title: "Routed apps",
    body: `<p>Every app the shared ingress is currently passing requests to, with its local port and
      the path it is reachable under. One shared link can therefore open several apps:
      <code>https://…/app-one/</code>, <code>https://…/app-two/</code>.</p>`,
  },
  "share-keys": {
    title: "API keys",
    body: `
      <p>An <strong>API key</strong> is a password for programs (scripts, other apps, the AI tools)
      that call your app directly instead of through the login page.</p>
      <p>"New key" creates one and shows it <strong>once</strong>; copy it then. Anyone holding a
      key can use the app's API, so share keys only with programs and people you trust.</p>`,
  },
  "share-deploy-vars": {
    title: "Settings you must supply",
    body: `
      <p>An app on a hosting company needs some settings that only you can know, like your database
      password or your domain. NPDev fills in everything it can work out by itself, and lists the
      rest here.</p>
      <p>You enter these in the hosting company's dashboard (usually called "Environment
      variables"). The written <code>.env.&lt;target&gt;.example</code> file lists them too.</p>`,
    links: [{ label: "Hosting guide: the environment contract", url: `${DOCS}/HOSTING.md#the-environment-contract` }],
  },
  "share-remote-check": {
    title: "Remote check",
    body: `<p>The same checks, run from outside against the address you deployed to: can it be
      reached, is HTTPS valid, does it refuse calls with no key? "Unknown" means it cannot be seen
      from outside. NPDev says so instead of guessing.</p>`,
  },

  // ------------------------------------------------------------------------------- Versions
  "versions-overview": {
    title: "What is the Versions tab?",
    body: `
      <p>NPDev itself (the generator, the command line and the runtime your apps are built from) is
      released in numbered <strong>versions</strong>. The Install tab (step 3) can download one; this
      tab lists the ones downloaded to this computer.</p>
      <p><strong>Why more than one?</strong> To try a newer version without losing the old one. If
      the new one breaks something, switch back with "Use this version". Apps are rebuilt with
      whichever version is in use the next time you run them.</p>
      <p><strong>Remove</strong> deletes that version's folder only. Your apps are never touched.</p>
      <p><strong>Using your own checkout?</strong> If "Use local repo" is on in Install step 3, the
      Manager uses that folder instead, and the versions listed here are not used at all until you
      turn it off.</p>`,
    links: [{ label: "All NPDev versions (tags)", url: "https://github.com/MarceloGiazzon/NPDevGeneral/tags" }],
  },
};

function helpButton(key) {
  return `<button type="button" class="help-btn" data-help="${key}" aria-label="What is this?" title="What is this?">?</button>`;
}

function helpDialog() {
  let dialog = document.getElementById("help-dialog");
  if (dialog) return dialog;
  dialog = document.createElement("dialog");
  dialog.id = "help-dialog";
  dialog.className = "help-dialog";
  // A click on the backdrop lands on the <dialog> itself; one inside lands on a child.
  dialog.addEventListener("click", (ev) => {
    if (ev.target === dialog) dialog.close();
  });
  document.body.appendChild(dialog);
  return dialog;
}

function openHelp(key) {
  const entry = HELP[key];
  if (!entry) return;
  const dialog = helpDialog();
  const links = (entry.links || [])
    .map((l, i) => `<li><a href="#" data-help-link="${i}">${l.label} &#8599;</a></li>`)
    .join("");
  dialog.innerHTML = `
    <div class="help-body">
      <h2>${entry.title}</h2>
      ${entry.body}
      ${links ? `<p class="help-more">Learn more:</p><ul class="help-links">${links}</ul>` : ""}
      <div class="help-actions"><button type="button" class="help-close">Close</button></div>
    </div>`;
  dialog.querySelector(".help-close").addEventListener("click", () => dialog.close());
  dialog.querySelectorAll("[data-help-link]").forEach((a) => {
    a.addEventListener("click", (ev) => {
      ev.preventDefault();
      // The system browser, never inside this window: the docs are a web page, not part of the app.
      helpInvoke("open_url", { url: entry.links[Number(a.dataset.helpLink)].url }).catch(() => {});
    });
  });
  dialog.showModal();
}

document.addEventListener("click", (ev) => {
  const btn = ev.target.closest && ev.target.closest(".help-btn");
  if (!btn) return;
  ev.preventDefault();
  ev.stopPropagation();
  openHelp(btn.dataset.help);
});
