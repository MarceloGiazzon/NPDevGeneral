/* NPDev business UI -- record Comments detail section (PROC-5).
 * Repo: NPDevGenerator/generator/src/main/resources/npdev-templates/static-assets/business-ui/views/comments.js
 * App:  static/npdev-business-ui/views/comments.js  (verbatim copy; NOT Mustache)
 *
 * Shown only when the concept's manifest node carries collaboration.comments (model:
 * `collaboration: { comments: true }`). Data: /api/records/{concept}/{id}/comments
 *   GET -> [{ id, author, body, kind, file, createdAtEpochMs, mine }] oldest first; POST {body}; DELETE /{commentId}.
 * With collaboration.attachments (PROC-6) an "Attach file" control posts multipart to
 *   /api/records/{concept}/{id}/attachments and ATTACHMENT rows (kind, file {name,size,contentType}) download from
 *   GET /api/records/{concept}/{id}/attachments/{commentId} -- gated by the record's read access.
 * The server gates every call by the record's OWN read access: a caller who cannot read the record
 * gets 404 here, which this section shows as "not available" rather than as an error.
 *
 * Comment bodies are plain text and are ONLY ever written with textContent.
 *
 * ctx (built by app.js openDetails): { concept, record, helpers: { fetchJson, headers, ... } }
 */
(function () {
  "use strict";

  function threadUrl(ctx) {
    return "/api/records/" + encodeURIComponent(ctx.concept.conceptName) + "/"
      + encodeURIComponent(ctx.record[ctx.concept.idField]) + "/comments";
  }

  // Same-origin calls through the shell's api client (token + CSRF header); the business UI's own
  // helpers are the fallback for a page that has no shell api.
  function call(ctx, method, url, body) {
    var api = window.NPDevShell && window.NPDevShell.api;
    if (api) {
      if (method === "GET") { return api.get(url); }
      if (method === "POST") { return api.post(url, body); }
      return api.del(url);
    }
    var init = { method: method, headers: ctx.helpers.headers() };
    if (body !== undefined) { init.body = JSON.stringify(body); }
    return ctx.helpers.fetchJson(url, init);
  }

  // PROC-6: multipart upload and gated download need the raw fetch: the shell api helpers are JSON-only.
  // Auth is the shell's token (Authorization + X-Api-Key) plus the SEC-1 CSRF header; NO Content-Type,
  // so the browser writes the multipart boundary itself.
  function authHeaders(ctx) {
    var api = window.NPDevShell && window.NPDevShell.api;
    var token = api && api.token ? api.token() : null;
    var headers = { "X-NPDev-Request": "1" };
    if (token) {
      headers["Authorization"] = "Bearer " + token;
      headers["X-Api-Key"] = token;
    } else if (ctx.helpers && ctx.helpers.headers) {
      var own = ctx.helpers.headers();
      Object.keys(own).forEach(function (name) { if (name.toLowerCase() !== "content-type") { headers[name] = own[name]; } });
    }
    return headers;
  }

  function attachmentUrl(ctx, item) {
    return "/api/records/" + encodeURIComponent(ctx.concept.conceptName) + "/"
      + encodeURIComponent(ctx.record[ctx.concept.idField]) + "/attachments/" + encodeURIComponent(item.id);
  }

  // A plain <a href> would send no Authorization header, so the file is fetched and opened as a blob.
  function downloadAttachment(ctx, item, status) {
    fetch(attachmentUrl(ctx, item), { headers: authHeaders(ctx) }).then(function (response) {
      if (!response.ok) { throw new Error("HTTP " + response.status); }
      return response.blob();
    }).then(function (blob) {
      var url = URL.createObjectURL(blob);
      var link = document.createElement("a");
      link.href = url;
      link.download = (item.file && item.file.name) || item.body;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
    }).catch(function (error) {
      status.textContent = "Could not download: " + (error && error.message ? error.message : "error");
    });
  }

  function humanSize(bytes) {
    if (bytes < 1024) { return bytes + " B"; }
    if (bytes < 1048576) { return Math.round(bytes / 1024) + " KB"; }
    return (bytes / 1048576).toFixed(1) + " MB";
  }

  function renderRow(ctx, item, reload, status) {
    var row = document.createElement("li");
    row.className = "comment-row" + (item.mine ? " comment-mine" : "");
    var head = document.createElement("div");
    head.className = "comment-head";
    head.textContent = item.author + " · " + new Date(item.createdAtEpochMs).toLocaleString();
    row.appendChild(head);
    var text = document.createElement("div");
    text.className = "comment-body";
    if (item.kind === "ATTACHMENT") {
      var open = document.createElement("button");
      open.type = "button";
      open.className = "comment-attachment";
      open.textContent = "📎 " +((item.file && item.file.name) || item.body)
        + (item.file ? " (" + humanSize(item.file.size) + ")" : "");
      open.addEventListener("click", function () { downloadAttachment(ctx, item, status); });
      text.appendChild(open);
    } else {
      text.textContent = item.body;
    }
    row.appendChild(text);
    if (item.mine) {
      var remove = document.createElement("button");
      remove.type = "button";
      remove.className = "comment-delete";
      remove.textContent = "Delete";
      remove.addEventListener("click", function () {
        remove.disabled = true;
        call(ctx, "DELETE", threadUrl(ctx) + "/" + encodeURIComponent(item.id)).then(reload, function () { remove.disabled = false; });
      });
      row.appendChild(remove);
    }
    return row;
  }

  function render(ctx) {
    var collaboration = ctx.concept && ctx.concept.collaboration;
    if (!collaboration || !collaboration.comments) { return null; }

    var section = document.createElement("section");
    section.className = "detail-comments";
    var title = document.createElement("h3");
    title.textContent = "Comments";
    section.appendChild(title);
    var list = document.createElement("ul");
    list.className = "comment-list";
    section.appendChild(list);

    var form = document.createElement("div");
    form.className = "comment-form";
    var input = document.createElement("textarea");
    input.className = "comment-input";
    input.rows = 2;
    input.maxLength = 4000;
    input.placeholder = "Write a comment — use @username to notify someone";
    var post = document.createElement("button");
    post.type = "button";
    post.textContent = "Comment";
    var status = document.createElement("div");
    status.className = "comment-status";
    form.appendChild(input);
    form.appendChild(post);
    section.appendChild(form);
    if (collaboration.attachments) {
      var attachRow = document.createElement("div");
      attachRow.className = "comment-attach";
      var picker = document.createElement("input");
      picker.type = "file";
      var attach = document.createElement("button");
      attach.type = "button";
      attach.textContent = "Attach file";
      attach.addEventListener("click", function () {
        if (!picker.files || !picker.files.length) { status.textContent = "Choose a file first."; return; }
        var data = new FormData();
        data.append("file", picker.files[0]);
        attach.disabled = true;
        fetch(threadUrl(ctx).replace(/\/comments$/, "/attachments"), {
          method: "POST", headers: authHeaders(ctx), body: data
        }).then(function (response) {
          return response.text().then(function (raw) {
            var parsed = null;
            try { parsed = raw ? JSON.parse(raw) : null; } catch (ignored) { /* not JSON */ }
            if (!response.ok) { throw new Error((parsed && parsed.message) || "HTTP " + response.status); }
          });
        }).then(function () {
          picker.value = "";
          attach.disabled = false;
          return load();
        }, function (error) {
          attach.disabled = false;
          status.textContent = "Could not attach: " + (error && error.message ? error.message : "error");
        });
      });
      attachRow.appendChild(picker);
      attachRow.appendChild(attach);
      section.appendChild(attachRow);
    }
    section.appendChild(status);

    function load() {
      return call(ctx, "GET", threadUrl(ctx)).then(function (items) {
        list.textContent = "";
        status.textContent = "";
        (Array.isArray(items) ? items : []).forEach(function (item) { list.appendChild(renderRow(ctx, item, load, status)); });
        if (!list.childNodes.length) { status.textContent = "No comments yet."; }
      }, function (error) {
        form.style.display = "none";
        status.textContent = error && error.status === 404 ? "Comments are not available for this record."
          : "Could not load comments: " + (error && error.message ? error.message : "error");
      });
    }

    post.addEventListener("click", function () {
      var body = input.value.trim();
      if (!body) { return; }
      post.disabled = true;
      call(ctx, "POST", threadUrl(ctx), { body: body }).then(function () {
        input.value = "";
        post.disabled = false;
        return load();
      }, function (error) {
        post.disabled = false;
        status.textContent = "Could not post: " + (error && error.message ? error.message : "error");
      });
    });

    load();
    return section;
  }

  window.NPDevViews.registerDetailSection("comments", { render: render });
})();
