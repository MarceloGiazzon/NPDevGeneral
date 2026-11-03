// "Get help with this error" (SUP-3).
//
// When a Manager operation fails, the user sees a button that opens a dialog showing EXACTLY what would
// be copied: a self-contained, redacted report that any AI chat (or a GitHub issue) can act on.
// Nothing is ever sent from here. The report itself is built in Rust (`support_report`, src/support.rs)
// from the run recorder, ui/support-intents.json and ui/help-glossary.json, and redacted there with the
// shared rules (NPDevCli/redaction-rules.json) -- this file only shows it and offers three buttons.
//
// Two entry points:
//   offerHelp(anchorEl, { operation, error, screen, appDir })
//       Adds the button right after an element that already shows the error text. Use this where the
//       existing code writes the message itself and the layout should not change.
//   reportableError(container, { operation, error, screen, appDir, message })
//       Renders the error line AND the button into `container`.
//
// `operation` is an id from support-intents.json (e.g. "npdev-setup"). `error` is whatever was caught;
// it is only a FALLBACK -- when the run recorder holds the operation, its richer record wins.

(function () {
  const t = (obj) => (window.NPDevI18n ? window.NPDevI18n.t(obj) : obj.en);
  const lang = () => (window.NPDevI18n && window.NPDevI18n.lang) || "en";

  const TEXT = {
    button: { en: "Get help with this error", pt: "Pedir ajuda com este erro" },
    title: { en: "Get help with this error", pt: "Pedir ajuda com este erro" },
    intro: {
      en: "This is exactly what will be copied. Passwords, keys and tokens were hidden before you saw it.",
      pt: "É exatamente isto que será copiado. Senhas, chaves e tokens foram ocultados antes de você ver.",
    },
    copy: { en: "Copy for AI", pt: "Copiar para uma IA" },
    copied: { en: "Copied. Paste it into your AI chat.", pt: "Copiado. Cole no seu chat de IA." },
    copyFailed: {
      en: "Could not reach the clipboard. Select the text and copy it by hand.",
      pt: "Não consegui usar a área de transferência. Selecione o texto e copie à mão.",
    },
    issue: { en: "Open a GitHub issue", pt: "Abrir uma issue no GitHub" },
    issueNote: {
      en: "The report was copied too. Paste it below the first lines on the page that opens.",
      pt: "O relatório também foi copiado. Cole-o abaixo das primeiras linhas na página que abrir.",
    },
    save: { en: "Save as file", pt: "Salvar como arquivo" },
    close: { en: "Close", pt: "Fechar" },
    nothing: {
      en: "Nothing is sent. You copy it and paste it wherever you want.",
      pt: "Nada é enviado. Você copia e cola onde quiser.",
    },
    building: { en: "Preparing the report…", pt: "Preparando o relatório…" },
    failed: { en: "Could not prepare the report", pt: "Não consegui preparar o relatório" },
    truncated: {
      en: "The report was shortened to fit a chat; the lines that matter most were kept.",
      pt: "O relatório foi encurtado para caber num chat; as linhas mais importantes foram mantidas.",
    },
  };

  function summary(out) {
    const s = out.secrets_hidden;
    const p = out.personal_shortened;
    const parts = [];
    if (lang() === "pt") {
      parts.push(`${s} ${s === 1 ? "segredo ocultado" : "segredos ocultados"}`);
      parts.push(`${p} ${p === 1 ? "dado pessoal encurtado" : "dados pessoais encurtados"}`);
    } else {
      parts.push(`${s} ${s === 1 ? "secret" : "secrets"} hidden`);
      parts.push(`${p} personal ${p === 1 ? "detail" : "details"} shortened`);
    }
    return parts.join(", ");
  }

  function ensureDialog() {
    let dlg = document.getElementById("support-dialog");
    if (dlg) return dlg;
    dlg = document.createElement("dialog");
    dlg.id = "support-dialog";
    dlg.setAttribute("translate", "no"); // support.js speaks the chosen language itself (t({en, pt}))
    dlg.className = "support-dialog";
    dlg.setAttribute("aria-labelledby", "support-title");
    dlg.innerHTML = `
      <form method="dialog" class="support-body">
        <h2 id="support-title"></h2>
        <p id="support-intro" class="support-intro"></p>
        <p id="support-summary" class="support-summary" aria-live="polite"></p>
        <label class="support-label" for="support-text" id="support-text-label"></label>
        <textarea id="support-text" class="support-text" readonly rows="16" spellcheck="false"></textarea>
        <p id="support-note" class="support-note" aria-live="polite"></p>
        <div class="support-actions">
          <button type="button" id="support-copy" class="primary"></button>
          <button type="button" id="support-issue"></button>
          <button type="button" id="support-save"></button>
          <button value="close" id="support-close"></button>
        </div>
        <p id="support-nothing" class="support-nothing"></p>
      </form>`;
    document.body.appendChild(dlg);
    return dlg;
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      // Fallback for a context without the async clipboard: select the textarea and ask the browser.
      const ta = document.getElementById("support-text");
      if (!ta) return false;
      ta.focus();
      ta.select();
      try {
        return document.execCommand("copy");
      } catch (e2) {
        return false;
      }
    }
  }

  function saveFile(name, text) {
    const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }

  async function openSupportDialog(opts, opener) {
    const dlg = ensureDialog();
    const $ = (id) => dlg.querySelector("#" + id);
    $("support-title").textContent = t(TEXT.title);
    $("support-intro").textContent = t(TEXT.intro);
    $("support-text-label").textContent = t(TEXT.title);
    $("support-copy").textContent = t(TEXT.copy);
    $("support-issue").textContent = t(TEXT.issue);
    $("support-save").textContent = t(TEXT.save);
    $("support-close").textContent = t(TEXT.close);
    $("support-nothing").textContent = t(TEXT.nothing);
    $("support-summary").textContent = t(TEXT.building);
    $("support-note").textContent = "";
    const textEl = $("support-text");
    textEl.value = "";
    for (const id of ["support-copy", "support-issue", "support-save"]) $(id).disabled = true;
    if (!dlg.open) dlg.showModal();

    let out;
    try {
      out = await window.__TAURI__.core.invoke("support_report", {
        operation: opts.operation || null,
        screen: opts.screen || currentScreenName(),
        appDir: opts.appDir || null,
        uiLanguage: lang(),
        error: opts.error == null ? null : String(opts.error),
      });
    } catch (err) {
      $("support-summary").textContent = `${t(TEXT.failed)}: ${err}`;
      return;
    }
    textEl.value = out.text;
    $("support-summary").textContent = summary(out) + (out.truncated ? ` · ${t(TEXT.truncated)}` : "");
    for (const id of ["support-copy", "support-issue", "support-save"]) $(id).disabled = false;

    $("support-copy").onclick = async () => {
      $("support-note").textContent = (await copyText(out.text)) ? t(TEXT.copied) : t(TEXT.copyFailed);
    };
    $("support-issue").onclick = async () => {
      await copyText(out.text);
      const url =
        "https://github.com/MarceloGiazzon/NPDevGeneral/issues/new?title=" +
        encodeURIComponent(out.issue_title) +
        "&body=" +
        encodeURIComponent(out.issue_body);
      try {
        await window.__TAURI__.core.invoke("open_url", { url });
        $("support-note").textContent = t(TEXT.issueNote);
      } catch (err) {
        $("support-note").textContent = String(err);
      }
    };
    $("support-save").onclick = () => saveFile(`npdev-error-report-${out.operation}.txt`, out.text);
    dlg.addEventListener(
      "close",
      () => {
        if (opener && document.contains(opener)) opener.focus();
      },
      { once: true }
    );
    $("support-copy").focus();
  }

  function currentScreenName() {
    const active = document.querySelector("section.screen.active, section[id^='screen-']:not([hidden])");
    return active ? active.id.replace(/^screen-/, "") : "unknown";
  }

  function makeButton(opts) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "support-help-btn";
    btn.textContent = t(TEXT.button);
    btn.addEventListener("click", () => openSupportDialog(opts, btn));
    return btn;
  }

  // Adds (or refreshes) the button right after `anchor`, which already shows the error text.
  function offerHelp(anchor, opts) {
    if (!anchor || !anchor.parentNode) return null;
    const next = anchor.nextElementSibling;
    if (next && next.classList.contains("support-help-btn")) next.remove();
    const btn = makeButton(opts);
    anchor.insertAdjacentElement("afterend", btn);
    return btn;
  }

  // Renders the error line plus the button into `container`.
  function reportableError(container, opts) {
    container.textContent = "";
    const line = document.createElement("p");
    line.className = "status-line support-error-line";
    line.setAttribute("role", "alert");
    line.textContent = opts.message || String(opts.error);
    container.appendChild(line);
    container.appendChild(makeButton(opts));
  }

  // Clears a button left behind by an earlier failure when the operation is retried.
  function clearHelp(anchor) {
    const next = anchor && anchor.nextElementSibling;
    if (next && next.classList.contains("support-help-btn")) next.remove();
  }

  window.NPDevSupport = { offerHelp, reportableError, clearHelp, openSupportDialog };
  window.offerHelp = offerHelp;
  window.reportableError = reportableError;
})();
