# Installing NPDev on a new machine

**For someone who has never used NPDev, and does not want to use a terminal.**

You do not need Java, Python, git, or any programming tools installed. NPDev Manager brings its own
Java and Python. Budget **up to an hour**: the installs are minutes, and the first build of your
first app is the slow part.

If you want the full manual instead — every screen, every setting, troubleshooting — read
[`MANAGER.md`](MANAGER.md). This page is the short path.

---

## Before you start

| | |
|---|---|
| **Windows** | Windows 10 or 11, 64-bit |
| **Linux** | Any modern 64-bit desktop |
| **Disk** | **5 GB free** — `npdev doctor` fails below this (NPDev downloads its own Java) |
| **Internet** | Needed throughout: the Java and Python downloads, the NPDev version list and download, and the first build of your first app |
| **Docker** | **Only if you want a server database** (Postgres, MySQL, SqlServer). Skip it — see step 5. |

Nothing else. If you already have Java installed, it does not matter which version — NPDev does not
use it.

---

## Step 1 — Download

Go to **[the latest release](https://github.com/MarceloGiazzon/NPDevGeneral/releases/latest)** and
download one file:

- **Windows** → the file ending `_x64-setup.exe`
- **Linux** → the file ending `_amd64.AppImage`

## Step 2 — Install

**Windows.** Double-click the `.exe`.

> Windows will probably say **"Windows protected your PC"**. This is because the app is not
> code-signed yet, not because anything is wrong with it. Click **More info**, then **Run anyway**.

Then launch **NPDev Manager** from the Start menu.

**Linux.** Open a file manager, right-click the `.AppImage` → Properties → tick *Allow executing
file as program*, then double-click it. (Or in a terminal: `chmod +x NPDev.Manager_*.AppImage &&
./NPDev.Manager_*.AppImage`.)

The AppImage includes its own browser engine (webkit2gtk), so there is nothing to install for that.
It does **not** bundle the graphics and text libraries every desktop already has — `libGLESv2`,
`libEGL`, `libgbm`, fontconfig, harfbuzz, fribidi. A normal desktop install has them; a minimal or
server image does not, and there the AppImage exits saying `Couldn't open libGLESv2.so.2`.

## Step 3 — Install the private runtimes

The Manager opens on the **Ready** screen, which is a list of checks. Go to the **Install** tab and
work down its four numbered steps **in order**:

| | Button | What it does |
|---|---|---|
| 1. Java 17 | **Install private JDK** | Downloads a JDK into the Manager's own folder (~180 MB). Nothing else on your computer is touched, and no PATH or registry setting is changed. |
| 2. Python | **Install private Python** | Only if your machine has none. |
| 3. NPDev | **Refresh list**, pick the newest, **Download this version** | Downloads NPDev itself as a zip. No git needed. |
| 4. Setup | **Run setup** | Prepares NPDev's own libraries and search index. **Do this last** — it needs steps 1–3 to have finished, and says so if you press it early. |

Step 4 is usually about a minute, occasionally about ten if it has to build the libraries instead of
downloading them.

## Step 4 — Check the Ready screen

Go back to **Ready** and press **Re-check**. Each row shows a mark, not a word:

| Mark | Meaning |
|---|---|
| **✓** | Fine. |
| **!** | Optional, or could not be checked. Something only some features need — usually ignore it. |
| **✗** | Must be fixed before you can build an app. The row says what to do. One row (**NPDev jars**) also has a **Fix this** button, which takes you to the Install tab rather than fixing anything itself. |

A **!** next to Docker is expected and fine — see the next step. A **!** next to git is also
expected: the Manager does not install git and nothing needs it, except your app's own version
history.

## Step 5 — Choose your database

When you create an app, NPDev asks which database to use. **This is the one choice that matters**,
so here it is plainly:

These are the six entries in the dropdown, in the order they appear there:

| Choice | Needs Docker? | Use it when |
|---|---|---|
| **H2Local** | **No** | **Start here.** It is the first entry and the default. Saves to a file beside your app; nothing to install. |
| H2Server | No | Special case: more than one process sharing one database. Ignore for now. |
| Postgres · MySQL · SqlServer | **Yes** | You already know you need one of these. MySQL and SqlServer are marked *(experimental)* in the list, and the Manager explains why under the dropdown. |
| InMemory | No | Nothing is kept when the app stops. Demos only. |

If you pick Postgres, MySQL or SqlServer, you must install
[Docker Desktop](https://www.docker.com/products/docker-desktop/) first — NPDev runs those databases
as containers. If you have not got Docker and do not want it, **pick H2Local**. Everything works.

> **Already run your own database server?** You can point NPDev at it and it will connect and work.
> **Do not press Start, Stop or Reset.** NPDev only knows a server is yours when
> `db.definition.json` declares `"externallyProvisioned": true`. **Since 2026-08-11 you can set
> it:** tick *"This server is mine, not NPDev's"* when creating the app, or pass
> `--externally-provisioned` to `npdev init`. Apps created BEFORE that still need the flag added by
> hand — so on a normal setup those buttons still act as if the server were NPDev's own, and
> **Reset deletes data**. If you want the protection today, add that flag to the app's
> `db.definition.json` by hand.

## Step 6 — Create, build, run

1. **Create app** — on the **Apps** tab, fill in **Name**, **Folder** (where the app is written —
   both are required), and the **Database** from step 5. Press **Create**.
2. **Run** — on the **Run** tab, put that folder in **App folder**, leave **Port** at `8080` unless
   something else is using it, and press **▶ Run**. The first run builds the app: it downloads
   dependencies and takes several minutes. Later runs are fast.
3. **Open it** — the **App:** link on that screen becomes `http://localhost:<the port you set>`.
   Click it.

If you chose Postgres, MySQL or SqlServer, the **Database** buttons on the Run screen only appear to
work after the app has been generated once — press **▶ Run** first and let it get as far as building,
then **Start** the database and run again.

That is a working application.

---

## If something goes wrong

**"Windows protected your PC"** → Expected. *More info → Run anyway*. See step 2.

**Setup fails to download** → Almost always a corporate network or VPN blocking GitHub. Try another
network.

**Ready says "NPDev jars — not staged"** → Setup has not finished. The **Fix this** button beside
that row takes you to the Install tab; press **Run setup** there.

**A database button says there is no `_ops` toolbox** → the app has not been generated yet. Press
**▶ Run** once, then try the database button again.

**A database button fails and you picked Postgres/MySQL/SqlServer** → Docker is not installed or
not running. Start Docker Desktop and wait for it to say *running*, then try again.

**The app builds but will not start** → the port is probably already in use. Nothing picks a port
for you: change **Port** on the Run screen to any free number and press **▶ Run** again — the
**App:** link follows whatever you set.

**Linux: the AppImage does nothing when double-clicked** → It is probably not marked executable.
See step 2.

---

## What to report back

This is pre-1.0 software, and the most useful thing you can tell us is **which of these two it was**:

- **"NPDev did the wrong thing"** — a defect, wherever it happens.
- **"it didn't work on my machine"** — the harder and more valuable kind. NPDev has been run on very
  few machines, and this category is the one we cannot find without you.

A screenshot and the step number from this page is enough.
