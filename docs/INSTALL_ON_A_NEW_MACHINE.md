# Installing NPDev on a new machine

**For someone who has never used NPDev, and does not want to use a terminal.**

You do not need Java, Python, or any programming tools installed. NPDev Manager brings its own.
About 15 minutes, most of it waiting for downloads.

If you want the full manual instead — every screen, every setting, troubleshooting — read
[`MANAGER.md`](MANAGER.md). This page is the short path.

---

## Before you start

| | |
|---|---|
| **Windows** | Windows 10 or 11, 64-bit |
| **Linux** | Any modern 64-bit desktop |
| **Disk** | **5 GB free** — `npdev doctor` fails below this (NPDev downloads its own Java) |
| **Internet** | Needed for the first run only |
| **Docker** | **Only if you want PostgreSQL, MySQL or SQL Server.** Skip it — see step 5. |

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

The AppImage includes its own browser engine, so there is nothing else to install.

## Step 3 — Run Setup

When the Manager opens, find **Setup** and click it.

It downloads a private copy of Java and Python **just for NPDev**, into its own folder. It does not
touch anything else on your computer, and it cannot break other programs. This is the slow step —
a few minutes on a normal connection.

## Step 4 — Run the doctor

Click **Doctor**. You want every row to say **pass**.

| Result | Meaning |
|---|---|
| **pass** | Fine. |
| **warn** | Optional. Something only some features need. Usually ignore it. |
| **fail** | Must be fixed before you can build an app. Each row has a button or a fix message. |

A **warn** next to Docker is expected and fine — see the next step.

## Step 5 — Choose your database

When you create an app, NPDev asks which database to use. **This is the one choice that matters**,
so here it is plainly:

| Choice | Needs Docker? | Use it when |
|---|---|---|
| **H2 (file)** | **No** | **Start here.** Saves to a file beside your app. Nothing to install. |
| H2 (server) · In-memory | No | Special cases; ignore for now. |
| PostgreSQL · MySQL · SQL Server | **Yes** | You already know you need one of these. |

If you pick PostgreSQL, MySQL or SQL Server, you must install
[Docker Desktop](https://www.docker.com/products/docker-desktop/) first — NPDev runs those databases
as containers. If you have not got Docker and do not want it, **pick H2 (file)**. Everything works.

> **Already run your own database server?** You can point NPDev at it and it will connect and work.
> **Do not press Start, Stop or Reset.** NPDev only knows a server is yours when
> `db.definition.json` declares `"externallyProvisioned": true`, and no Manager screen can set
> that yet — so on a normal setup those buttons still act as if the server were NPDev's own, and
> **Reset deletes data**. If you want the protection today, add that flag to the app's
> `db.definition.json` by hand.

## Step 6 — Create, build, run

1. **Create app** — give it a name, pick the database from step 5.
2. **Build** — the first build downloads dependencies and takes a few minutes. Later builds are fast.
3. **Run** — the Manager shows the web address. Open it in your browser.

That is a working application.

---

## If something goes wrong

**"Windows protected your PC"** → Expected. *More info → Run anyway*. See step 2.

**Setup fails to download** → Almost always a corporate network or VPN blocking GitHub. Try another
network.

**Doctor says "NPDev jars — not staged"** → Setup has not finished. Click the button beside that row.

**A database button fails and you picked PostgreSQL/MySQL/SQL Server** → Docker is not installed or
not running. Start Docker Desktop and wait for it to say *running*, then try again.

**The app builds but will not start** → Check the port is not already in use. The Manager shows the
port it chose.

**Linux: the AppImage does nothing when double-clicked** → It is probably not marked executable.
See step 2.

---

## What to report back

This is pre-1.0 software, and the most useful thing you can tell us is **which of these two it was**:

- **"NPDev did the wrong thing"** — a defect, wherever it happens.
- **"it didn't work on my machine"** — the harder and more valuable kind. NPDev has been run on very
  few machines, and this category is the one we cannot find without you.

A screenshot and the step number from this page is enough.
