# NPDev — handover to a second machine

You have been given a tag, two installers, and `STABILITY_MANIFEST.json`. This page is the whole
job: **install it, create an app, run it, tell us what happened.**

It should take under an hour, most of which is a download and a first build.

> **Read §4 before you report anything.** Some things on this page are *known* not to work. A known
> limit reported as a bug costs us a day; an *unknown* one reported as a bug is the entire reason
> you are doing this. We need you to be able to tell them apart — so we have written down which is
> which, in advance.

---

## 1. What you need before you start

Nothing. That is the claim being tested.

Specifically: **no Java, no Python, no PowerShell, no Gradle, no git.** The Manager installs a
private JDK and Python into its own folder and never touches your PATH, your registry, or any
system setting. If at any point you are told to install something that is not on this page, **that
is a finding** — write it down and keep going if you can.

You do need:

- Windows 10/11 (`NPDev.Manager_*_x64-setup.exe`) **or** Linux x86-64 (`NPDev.Manager_*.AppImage`)
- about 6 GB free disk
- an internet connection

---

## 2. Do this

### 2.1 Install and open the Manager

**Windows:** run the `.exe`. It is not signed, so SmartScreen will warn you — "More info" →
"Run anyway". *(That warning is expected. It is not a finding.)*

**Linux:** `chmod +x NPDev.Manager_*.AppImage` then run it.

It has been launched exactly once, on 2026-08-09, and only in a container: Ubuntu 22.04 with a
standard desktop GL/GTK runtime, under a virtual display, with **no webkit2gtk installed** — it
started and stayed running. That proves the bundle is self-contained. It does **not** prove it works
on your desktop, with your GPU, your display server and your distro. **You are the first person
running it for real**, which is why §4's row about it is worth reading before you report anything.

### 2.2 Ready

The first screen lists every check, including the ones that pass. Each row carries a mark rather
than a word — **✓** pass, **!** warn, **✗** fail, **–** not applicable. Anything red has a sentence
telling you what to do, and one row (**NPDev jars**) has a **Fix this** button, which takes you to
the screen where you fix it rather than fixing it for you. **Re-check** re-runs everything.

> Green here means *this machine can build NPDev apps*. It does not yet mean anything has been built.

### 2.3 Install → the four steps, in order

1. **Java 17** — "Install private JDK". Downloads into the Manager's own folder.
2. **Python** — only offered if you have none.
3. **NPDev** — pick the newest version, "Download this version".
4. **Setup** — "Run setup". It stages the jars every generated app compiles against, either by
   downloading them from the release (**about a minute**) or by building them locally (**about
   ten**) if that download is unavailable. A progress log appears and says which path it took; if it
   sits with no output for more than ~15 minutes, that is a finding.

### 2.4 Create an app

Apps tab → name it, choose a folder, and pick a **Database**:

- **H2Local** — the default. No server, nothing to install. **Use this first.**
- **Postgres / MySQL / SqlServer** (the picker's own spellings) — need a server running. Fill in host/port/user/password and
  press **Test connection** *before* creating. It answers in seconds and tells you which of
  reachable / credentials / database-exists / privileges / charset is wrong.

Press **Create**.

### 2.5 Run it

Run tab → point "App folder" at the folder you just created → **▶ Run**.

First run builds the app, so **expect several minutes**. When the log says ready, open the **App:**
link on that screen. It is `http://localhost:` plus whatever is in the **Port** field — 8080 unless
you changed it. Nothing picks a port for you, so if 8080 is taken on your machine, change it here
and run again.

If you chose a server database, the **Database** buttons on that same screen drive the app's own
tooling — which the app does not have until it has been generated once. So the order is: **▶ Run**
first (let it build; it may fail to start without its database, which is fine), then **Start**, then
**▶ Run** again. **Connection details** is there if you want to inspect the database in a tool like
DBeaver.

> **If you pointed NPDev at a database server you already run: do not press Stop or Reset.** NPDev
> only knows a server is not its own when the app's `db.definition.json` says
> `"externallyProvisioned": true`, and no screen can set that yet — so **Reset deletes data**.

### 2.6 Change one thing

In the app folder, open `model.json`, add a field, save. The app rebuilds and restarts on its
own. Confirm the change is visible in the browser.

*(Add, don't rename. A rename with nothing declaring it a rename reads as drop-plus-add — a
destructive change — and NPDev refuses it on purpose. That refusal is §4 territory, not a bug; see
`docs/YOUR_FIRST_APP.md` for how to declare one.)*

**That is the whole product.** If you got here, it works.

### 2.7 One last thing, and it is for us rather than for you

Open the **The Monitor** tab. It is one card per app on this machine. Find yours and confirm the
card says something truthful — running or not, its port, its database.

Then on that card: **☰ ACTIONS → Export support bundle**. It writes one zip beside the app: the app's own run logs,
the output of any database button you pressed, the Manager's log, and the database plan **with the
password removed**. Send us that zip whether or not anything went wrong — it is the cheapest
complete picture of what this machine actually did, and this step exists partly so that someone
other than the author has confirmed the redaction really happens.

*(Terminal equivalent: `npdev monitor logs export --app-dir <the app folder> --out support.zip`.)*

---

## 3. What to send back

Whatever happened, send:

1. **Which step you reached**, by number.
2. **Your machine** — OS and version, and whether it already had Java/Python/PowerShell/git.
3. **The exact text** of anything red. A screenshot is fine; a photo of a screen is fine.
4. **How long §2.3 step 4 and §2.5 took.** Minutes is precise enough.
5. **The support bundle from §2.7.**
6. **Anything you had to figure out that this page did not tell you.** ← *This is the most valuable
   thing you can report, and the easiest to forget, because by the time it works you have stopped
   noticing that you solved it.*

Do **not** try to fix anything. A workaround you apply silently is a defect we never see.

---

## 4. Known limits — these are NOT bugs

| What | Why | Report it? |
|---|---|---|
| The Windows installer is unsigned; SmartScreen warns | No code-signing certificate yet | No |
| The AppImage needs a normal desktop graphics stack | It **does** bundle `webkit2gtk` (measured — `libwebkit2gtk-4.1.so.0` and the WebKit helper processes are inside it), so you do **not** need to install that. It does *not* bundle the libraries every desktop already has: `libGLESv2`/`libEGL`, `libgbm`, fontconfig, harfbuzz, fribidi. Any normal desktop install has them; a minimal/server image may not. `libGLESv2` is loaded at runtime, so it aborts with `Couldn't open libGLESv2.so.2` rather than a linker error | Only if you are on a **desktop** install and it still fails |
| The five **Database** buttons need PowerShell | They drive the app's own generated `_ops` scripts. Windows always has one. A Linux desktop usually does not, and the buttons will say so rather than working. | Only if the message is confusing |
| MySQL/H2: a failed migration cannot be rolled back | Those engines commit implicitly on DDL. NPDev reports this truthfully instead of claiming a rollback (`STOR-2`) | No |
| Seven unused internal database methods | Four are answers prepared before any consumer exists, three are exercised by the dialect conformance vectors instead of production code. Deliberate and recorded, no user-visible effect (`STOR-13`) | No |
| The first build is slow | Gradle is populating a cold cache. Later builds are much faster | No |
| Ready shows `git-present` as a **warning**, and `npdev init` says it made no repository | The Manager never installs git and nothing needs it — apps scaffold, build and run without it. Only your app's own version history is affected | No |
| §2.6 asks you to **add** a field, not rename one | A rename with nothing declaring it a rename reads as drop-plus-add, and NPDev refuses destructive schema changes on purpose (`docs/ACCEPTED_BOUNDARIES.md` B1). If you rename one anyway, the refusal is the product working | No |

**Anything not in this table is worth reporting**, including "the wording confused me".

---

## 5. If it will not start at all

Send us the output of these and stop:

```
# Windows — the Manager's own log
%LOCALAPPDATA%\NPDev\logs\   →  send manager.log

# Linux — the same folder, plus running the AppImage from a terminal so you can see why it exited
~/.local/share/npdev/logs/   →  send manager.log
./NPDev.Manager_*.AppImage
```

**If the Manager starts but an APP will not**, there is now one better thing to send. Open The
Monitor, find the app's card, and use **Actions → Export support bundle**. That writes a single zip
next to the app containing its own run logs, the output of every `_ops` script you ran from the
window, the Manager's log, and what the probe saw — with the database password redacted, because
the whole point of the file is that it leaves your machine. Send that one file.

From a terminal it is the same thing:

```
npdev monitor logs export --app-dir <the app folder> --out support.zip
```

*(This section named `%LOCALAPPDATA%\npdev-manager\` until 2026-08-10 — a folder that does not
exist; the Manager's home is `%LOCALAPPDATA%\NPDev`. It also asked for `.log` files that nothing had
ever written. Both are fixed: the Manager writes `manager.log` from this release onward, and a
generated app now keeps its own run logs under `<app>\logs\`.)*

A refusal to start is a **complete** result, not a failed session. It is the single most useful
thing this exercise can find.

---

## 6. What we already know we have not checked

`STABILITY_MANIFEST.json`, shipped beside this file, has a `notVerified` list. It is deliberately
non-empty and worth two minutes of your time: it says, in advance, what nobody has tested — which
tells you where you are most likely to be the first person to find something.

Its `verified` list carries **CI run ids**, not adjectives. If you want to check whether a claim
was actually proven, that is where to look.
