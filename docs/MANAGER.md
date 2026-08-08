# NPDev Manager — Manual

Installs and runs NPDev on a machine that has nothing on it — no Java, no Python, no git — and
never asks you to open a terminal.

**Windows · Linux · 5 screens · 21 actions · Private runtimes**

## Contents

- [What it is](#what-it-is)
- [Installing it](#installing-it)
- [1 · Ready](#1--ready)
- [2 · Install](#2--install)
- [3 · Apps](#3--apps)
- [4 · Run](#4--run)
- [5 · Versions](#5--versions)
- [Files & folders](#files-and-folders)
- [The ten checks](#the-ten-checks)
- [Commands it runs](#commands-it-runs-for-you)
- [Action reference](#action-reference)
- [Progress events](#progress-events)
- [Environment variables](#environment-variables)
- [Preview mode](#preview-mode)
- [Troubleshooting](#troubleshooting)
- [Uninstalling](#uninstalling)
- [What it doesn't do](#what-it-deliberately-does-not-do)

## What it is

A small desktop application that gets NPDev onto your computer and keeps it working. It is not
NPDev itself, and it is not the editor you author models in.

Building an NPDev application normally needs Java 17, Python 3, git, and a terminal. The Manager
removes all four. It downloads its own copy of Java and Python into a single folder, fetches
NPDev as a zip file, creates an app from a form, runs it, and shows you the log in a window.

**Nothing is installed system-wide.** No PATH changes, no registry entries, no administrator
prompt. The private Java is used only by the programs the Manager starts, which is why a wrong
Java version on your machine cannot break it. Removing everything means deleting one folder.

### How it relates to the other pieces

| Piece | What it does | When you use it |
|---|---|---|
| **NPDev Manager** | Installs NPDev, creates apps, runs them | Before anything exists |
| **NPDev** (the CLI) | Validates models, generates and runs apps | The Manager calls it for you |
| **NPDev Editor** | Edits a model in the browser | Inside an app that is already running |

Every button in the Manager runs a command that also works on its own in a terminal. The Manager
adds no behaviour of its own beyond downloading runtimes and showing you output — so nothing you
do here is a dead end if you later want the command line.

## Installing it

One download. No prerequisites.

### Windows

1. [Download the installer](https://github.com/MarceloGiazzon/NPDevGeneral/releases/download/beta1.7/NPDev.Manager_0.1.0_x64-setup.exe)
   (`.exe`).
2. Run it. Windows may show **"Windows protected your PC"** — the application is not yet
   code-signed. Choose **More info → Run anyway**.
3. Launch **NPDev Manager** from the Start menu.

### Linux

1. Download the `.AppImage`.
2. Make it executable and run it:

```bash
chmod +x NPDevManager.AppImage
./NPDevManager.AppImage
```

The AppImage carries its own browser engine, so there is nothing to install first.

> **Not yet published as of 2026-08-07.** Building the Linux AppImage needs a Linux build host —
> this manual was written and the Windows installer built from a Windows machine, so no `.AppImage`
> release asset exists yet. See `docs/RELEASE_PROCESS.md`'s "Publishing the Manager" section for
> the manual publish steps once one is built.

**First run creates one folder and touches nothing else.** Windows: `%LOCALAPPDATA%\NPDev`.
Linux: `~/.local/share/npdev`. Everything the Manager downloads lives there.

### The five screens, in the order you will use them

The window has five tabs across the top. A first run goes through them left to right: check the
machine, install NPDev, create an app, run it. **Versions** is for later, when you want to update.

## 1 · Ready

Ten checks that tell you whether this machine can build an NPDev app — before you spend time
finding out the hard way.

```
  Java 17+           pass    17.0.11   (private JDK)
  JAVA_HOME          pass    agrees
  Python 3.9+        pass    3.11.9
  git                pass    2.44.0
  Disk space         pass    42 GB free
  NPDev jars         fail    not staged     [ Run setup ]
  AI knowledge index warn    not built      [ Run setup ]
  Docker             warn    not found      optional
  PowerShell 7       warn    not found      optional
```

### What the three states mean

| State | Meaning | What to do |
|---|---|---|
| **pass** | Requirement met. | Nothing. |
| **fail** | You cannot build an app until this is resolved. | Use the button beside it, or follow the fix text. |
| **warn** | Optional. Something is missing that only some paths need. | Usually nothing. Docker and PowerShell are never required. |

### Controls

- **Refresh** — Re-runs every check. Use it after installing something.
- **Fix button** — Appears only on rows the Manager can resolve itself. Runs the command shown on
  that row.

**Passing rows are shown on purpose.** A list that displayed only problems would leave you unable
to tell a healthy machine from a check that never ran.

The full list of checks, with what makes each one fail, is in [the reference section](#the-ten-checks).

## 2 · Install

Gets Java, Python, and NPDev itself. Run once per version.

### What each button does

| Button | What happens | Where it lands |
|---|---|---|
| **Install private JDK** | Downloads Java 17 for your platform, verifies the checksum, extracts it. | `<home>/jdk-17` |
| **Install private Python** | Only if your machine has no Python 3.9+. Otherwise it uses the one you have. | `<home>/python` |
| **Refresh list** | Fetches available NPDev versions from GitHub. | — |
| **Download this version** | Downloads the selected version as a zip and unpacks it. | `<home>/versions/<tag>` |
| **Run setup** | Prepares NPDev's internal build files. Progress appears line by line. | `<home>/runtimehost-libs` |

### Order

1. **Install private JDK** — a few minutes, roughly 180 MB.
2. **Install private Python** — skip if the Ready screen already shows Python passing.
3. **Refresh list**, pick the newest version, **Download this version**.
4. **Run setup**.

**Setup takes either about a minute or about ten.** Released versions include prebuilt files it
can download; anything else has to be built on your machine. The log says which happened — look
for `Jars source: download` or `Jars source: build`. Both end in a working install.

When setup finishes, return to **Ready** and press Refresh. **NPDev jars** should now pass.

## 3 · Apps

Your applications. Each one is a folder with a model file in it — that file is the app.

### Creating one

| Field | Meaning | Example |
|---|---|---|
| **Name** | Folder name for the new app. Letters, digits, hyphens. | `my-library` |
| **Folder** | Where to create it. Anywhere you can write. | `C:\Users\ana\Projects` |
| **Create** | Scaffolds the app and starts a git history for it. | — |

#### What Create produces

| File | What it is |
|---|---|
| `model.json` | **Your application.** Concepts, fields, rules, screens. |
| `config.json` | Build settings. |
| `db.definition.json` | Database settings. Set up to keep your data between runs. |
| `README.md` | What this app is and the next command. |
| `.gitignore` | Keeps generated output out of version control. |

**The model file is the part worth keeping.** Generated code can be rebuilt from it at any time;
the model cannot be rebuilt from anything. Create starts a git repository in the folder for
exactly this reason — commit as you go.

### Controls

- **Open folder** — Opens the app in your file manager.
- **Run** — Switches to the Run screen with this app selected.

## 4 · Run

Builds your app, starts it, and watches the model file. Save a change and it rebuilds itself.

```
  App folder  C:\Users\ana\Projects\my-library      Port  8080

  [ Start ]   [ Stop ]        →  http://localhost:8080

  14:09:02  changed: model.json
  14:09:02  validate ................................. ok
  14:09:02  structural change — full rebuild
  14:09:47  ready in 45.2s   http://localhost:8080
```

### Controls

| Control | What it does |
|---|---|
| **App folder** | Which app to run. |
| **Port** | Where it will be reachable. Default `8080`. Change it if something else is using that port. |
| **Start** | Builds, starts, then watches the model for changes. |
| **Stop** | Stops the app and everything it started. |
| **Link** | Opens the running app in your browser. |

### The first start takes longer

Two to four minutes the first time — it is building an entire application. After that, saving a
change rebuilds in seconds when the change is cosmetic, or under a minute when it changes the
database.

### Signing in

Open the link. Your key is in a file called `SUPER_USER_KEY.txt`, created in the app folder the
first time it starts.

**A mistake will not cost you the running app.** The model is checked before anything is
rebuilt. If you save something invalid, the log shows the error — naming the exact place — and
your app keeps running on the last version that worked. Fix it and save again.

### Reading the log

| Line | Meaning |
|---|---|
| `changed: model.json` | A save was noticed. |
| `validate ... ok` | The model is valid; rebuilding. |
| `validate ... FAILED` | Something is wrong. The error follows. The app stays up. |
| `METADATA_ONLY` | A cosmetic change — the fast path, a few seconds. |
| `structural change` | The database changed too — a full rebuild. |
| `ready in 45.2s` | Running again. Refresh your browser. |

## 5 · Versions

Which NPDev versions you have, which one is in use, and how to add or remove one.

| Control | What it does |
|---|---|
| **Installed list** | Every version you have downloaded. The current one is marked. |
| **Set current** | Chooses which version new builds use. |
| **Install** | Downloads another version alongside the ones you have. |
| **Remove** | Deletes that version's folder. |

Versions sit side by side in separate folders, so switching is instant and nothing is overwritten.
Your apps are stored separately and are never touched by any of this.

**NPDev is pre-1.0 and changes deliberately.** A new version can change the model format. When
that happens the release notes say so, and NPDev ships an automatic converter for existing
models — but keep your model in git before updating.

## Files and folders

Everything the Manager creates lives in one place.

| Platform | Location |
|---|---|
| Windows | `%LOCALAPPDATA%\NPDev` |
| Linux | `~/.local/share/npdev` |
| Override | Set `NPDEV_MANAGER_HOME` to any path |

```
<home>/
  manager.json          which version is current, and install history
  jdk-17/                the private Java — used only by NPDev
  python/                the private Python — only if you had none
  versions/
    beta1.6/              one downloaded version
    beta1.7/              another, side by side
  runtimehost-libs/      NPDev's internal build files
  apps/                   apps created without choosing a folder
  logs/                   what happened, for troubleshooting
```

Your own apps live wherever you chose when creating them. Nothing outside this folder is
modified, and no system settings are changed.

## The ten checks

What the Ready screen tests, and what makes each one fail.

| Check | Needs | Fails when | Fix |
|---|---|---|---|
| `java-present` | Java installed | No Java found at all | Install private JDK |
| `java-version` | Version 17 or newer | Java is present but older than 17 (a newer major version warns instead of failing — see below) | Install private JDK |
| `java-home-agreement` | Consistent Java | **warn** Two different Javas in play | Use the private JDK |
| `python-version` | Python 3.9+ | Older or missing | Install private Python |
| `git-present` | git installed | Not found | Only needed to start an app's history |
| `disk-space` | A few GB free | Below the threshold | Free up space |
| `runtimehost-jars` | Setup has run | Not yet prepared | **Run setup** |
| `ai-knowledge-index` | Setup has run | **warn** Not built | Run setup — only needed for AI authoring |
| `docker-present` | Optional | **warn** Not found | Nothing — an alternative run path only |
| `pwsh-present` | Optional | **warn** Not found | Nothing — never required |

**Wrong-Java used to be the classic failure; the Manager mostly removes it now.** The private JDK
is passed only to the programs NPDev starts, so whatever Java is on your machine is irrelevant —
including none at all. A Java newer than 17 on your machine now **warns** rather than fails (the
generated app's own build can target any Java version 17 or newer per-app, and Gradle's toolchain
resolver can provision whichever one a given app needs), so a warn here is informational, not a
blocker.

## Commands it runs for you

Every button maps to a command that works on its own in a terminal. Nothing here is Manager-only.

| Screen | Button | Command |
|---|---|---|
| Ready | Refresh | `npdev doctor --json` |
| Install | Run setup | `npdev setup --json` |
| Apps | Create | `npdev init <folder> --json` |
| Run | Start | `npdev dev --port <port> --json` |

Each runs with the private Java and Python supplied through the environment, from the version
folder you have marked as current. If you ever prefer the terminal, the same commands work
there — the Manager is a convenience, not a dependency.

## Action reference

The complete internal surface — useful when reporting a problem or reading the logs.

#### Checks and status

| Action | Purpose |
|---|---|
| `check_doctor` | Run all ten checks |
| `jdk_status` | Is the private Java installed |
| `python_status` | Is Python available or installed |
| `is_fake_mode` | Whether preview mode is on |

#### Installing

| Action | Purpose |
|---|---|
| `install_jdk` | Download and extract Java 17 |
| `install_python` | Download and extract Python |
| `list_tags` | Available NPDev versions from GitHub |
| `install_npdev_version` | Download and unpack a version |
| `run_setup` | Prepare NPDev's build files |

#### Apps and running

| Action | Purpose |
|---|---|
| `list_apps` | Known apps |
| `create_app` | Scaffold a new app |
| `start_dev` | Build, start, and watch |
| `stop_dev` | Stop the app and its children |
| `open_folder` | Open in the file manager |
| `open_url` | Open in the browser |

#### Versions and preview mode

| Action | Purpose |
|---|---|
| `list_installed_versions` | What is downloaded |
| `current_version` | Which one is in use |
| `set_current_version` | Switch versions |
| `remove_installed_version` | Delete a version |
| `fake_doctor_scenarios` | List preview scenarios |
| `set_fake_doctor_scenario` | Choose one |

## Progress events

Long operations stream progress rather than freezing. These names appear in the logs.

| Event | Emitted during |
|---|---|
| `jdk-progress` | Downloading and extracting Java |
| `python-progress` | Downloading and extracting Python |
| `version-install-progress` | Downloading an NPDev version |
| `setup-event` | Each phase of setup |
| `dev-event` | Every line of the run log |

**If a progress bar never moves, that is worth reporting.** These events are how the window
knows anything is happening; silence means something upstream stopped, not that the operation is
merely slow.

## Environment variables

| Variable | Effect | Default |
|---|---|---|
| `NPDEV_MANAGER_HOME` | Where everything is stored | `%LOCALAPPDATA%\NPDev` / `~/.local/share/npdev` |
| `NPDEV_MANAGER_FAKE` | Preview mode — set to `1` | off |

Set `NPDEV_MANAGER_HOME` to keep everything on a different drive, or to run two independent
installations side by side.

## Preview mode

Explore every screen without installing anything.

```bash
# Windows
set NPDEV_MANAGER_FAKE=1 && NPDevManager.exe

# Linux
NPDEV_MANAGER_FAKE=1 ./NPDevManager.AppImage
```

Nothing is downloaded and no commands are run. A banner marks the window as preview. The Ready
screen can be switched between prepared situations:

| Scenario | Shows |
|---|---|
| `all-green` | A fully prepared machine |
| `missing-java` | No Java at all |
| `wrong-java` | An older Java than 17 installed |
| `acceptable-newer-java` | A newer Java than 17 installed (warns, does not fail) |
| `no-jars` | Setup has not been run yet |

Useful for a demonstration, for learning the screens before committing to a download, or for
seeing what a failure looks like on a machine where nothing is wrong.

## Troubleshooting

### Windows says the app is not trusted

Choose **More info → Run anyway**. The application is not yet code-signed, which is a purchase
and a release process rather than a defect.

### The Linux AppImage will not start

Make sure it is executable: `chmod +x NPDevManager.AppImage`. If it still refuses, run it from a
terminal — the error printed there will name what is missing.

### "NPDev jars — not staged" after running setup

Press **Refresh** on the Ready screen; results are from the last run. If it persists, open
**Install** and read the setup log for the first red line.

### The app will not start on port 8080

Something else is using that port. Change the port on the Run screen — any free number works,
and the link updates with it.

### A download fails or stalls

Press the button again; downloads restart cleanly and a partial file is discarded rather than
reused. Behind a corporate proxy, the GitHub and Adoptium addresses need to be reachable.

### "An app from a previous session is still running"

Normal after a crash. The Manager finds the old app and stops it before starting a new one. No
action needed.

### Something else

The `logs/` folder in the Manager's home directory keeps what happened. Include the relevant
file and the version shown on the Versions screen when reporting a problem.

## Uninstalling

1. Stop any running app (**Run → Stop**).
2. Delete the Manager's home folder — `%LOCALAPPDATA%\NPDev` or `~/.local/share/npdev`.
3. Windows: uninstall **NPDev Manager** from Settings. Linux: delete the AppImage.

That is everything. Nothing was placed elsewhere, no system settings were changed, and the
private Java and Python go with the folder.

**Your apps are not in that folder** unless you chose to put them there. Apps created in your own
directories are untouched — and since the model file is the app, they can be rebuilt at any time
by installing NPDev again.

## What it deliberately does not do

| Not this | Use instead |
|---|---|
| Edit models | The NPDev Editor, inside your running app |
| Deploy to a server | NPDev's deployment documentation |
| Run several apps at once | One at a time; stop before starting another |
| Update itself | Download a newer Manager |
| macOS | Not yet supported |

**The Manager's job is to stop being needed.** It carries you from an empty machine to a running
application, then gets out of the way — the editor takes over for authoring, and everything it
did is a command you could have typed yourself.

---

NPDev Manager · Windows and Linux · Pre-1.0, under active development.
Five screens, twenty-one actions, five progress events, ten checks.
