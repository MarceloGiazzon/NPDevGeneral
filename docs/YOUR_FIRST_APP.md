# Your first app

You have run the demo. Now build something of your own.

This takes about 15 minutes and produces a working application — database, REST API, admin
screens, login — from a file you can read.

## What you are actually building

An NPDev app is described by one file: `model.json`. Everything else is generated from it.

**That file is your application.** The generated code is disposable — you can delete and
regenerate it any time. The model is the part worth keeping, backing up, and putting in git.

We will build a small library: **Books** and the **Members** who borrow them.

## 1. Scaffold it

```sh
./npdev init ../my-library
```

This one command gives you `model.json` (a small, valid, already-runnable example), `config.json`,
`db.definition.json`, a `README.md`, a `.gitignore` — and a git history: the directory is already a
repository with one commit. **That matters:** your model is the app, and the version you'd most
want back if you broke something is the one you never lost.

**Without `db.definition.json`, `generate app` would silently default to an in-memory database
that forgets everything the moment the app stops.** `npdev init` gives you a real one (H2Local)
from the start, for exactly the reason step 5 below needs it: proving your data survives a schema
change.

## 2. Make it yours

Open `../my-library/model.json`. Replace its `concepts` array — the scaffold's own Patient/
Appointment example — with two of your own:

```json
"concepts": [
  {
    "name": "Book",
    "ui": { "label": "Book" },
    "fields": [
      { "name": "id", "type": "uuid", "id": true, "required": true },
      { "name": "title", "type": "string", "required": true, "ui": { "label": "Title" } },
      { "name": "isbn", "type": "string", "ui": { "label": "ISBN" } },
      { "name": "copies", "type": "int", "ui": { "label": "Copies" } }
    ],
    "invariants": [
      { "name": "BookIsbnUnique", "type": "unique", "fields": ["isbn"] }
    ]
  },
  {
    "name": "Member",
    "ui": { "label": "Member" },
    "fields": [
      { "name": "id", "type": "uuid", "id": true, "required": true },
      { "name": "fullName", "type": "string", "required": true, "ui": { "label": "Full name" } },
      { "name": "email", "type": "string", "ui": { "label": "Email" } }
    ]
  }
]
```

**The scaffold's seed is deliberately just concepts — nothing that references them from elsewhere.**
Replacing the whole array is safe: there is no `domainTypes`/`capabilities`/`flows` block anywhere
in the scaffold that points back at Patient or Appointment and would break when they disappear.

## 3. Check it before you build it

```sh
cd ../NPDevGeneral
./npdev validate model ../my-library/model.json
```

Expect `"status": "passed"`.

**If it fails, read the `message` and `suggestedFix` on each diagnostic** — they name the exact
path in your file. Fix and re-run. Getting errors here is normal and cheap; getting them after a
five-minute build is not.

## 4. Build and run it

```sh
./npdev generate app --model ../my-library/model.json --config ../my-library/config.json --output ../my-library-app
cd ../my-library-app
./gradlew bootJar
java -jar build/libs/FinalExec-0.1.0.jar --spring.profiles.active=dev
```

Open **http://localhost:8080**. The `dev` profile ships a fixed convenience key for exactly this
moment — type **`dev-key`** into the API Key field on the page that greets you.

**You should see Book and Member as real screens** — list, create, edit — with a database and a
REST API behind them. (There is a second, separate key file — `SUPER_USER_KEY.txt` — but that is
for the platform ControlPanel, not for using your own app; ignore it for now.)

## 5. Change it and watch it follow

This is the part that matters. Stop the app (**Ctrl+C, and give it a couple of seconds** — on
Windows especially, regenerating before the old process has fully released its jar file fails with
a file-in-use error), and add a field to `Book` in `model.json`:

```json
{ "name": "publishedYear", "type": "int", "ui": { "label": "Published year" } }
```

Then validate, regenerate, and restart:

```sh
cd ../NPDevGeneral
./npdev validate model ../my-library/model.json
./npdev generate app --model ../my-library/model.json --config ../my-library/config.json --output ../my-library-app
cd ../my-library-app && ./gradlew bootJar && java -jar build/libs/FinalExec-0.1.0.jar --spring.profiles.active=dev
```

**The Book screen now has "Published year". The column exists. The API accepts it. Your existing
book is still there** (with `publishedYear` blank, since it existed before the field did) — NPDev
evolves the schema rather than recreating it.

Commit the change:

```sh
cd ../my-library && git commit -am "add publishedYear to Book"
```

## 6. Renaming — read this before you need it

**NPDev cannot guess that a rename is a rename.** If you change `isbn` to `isbnCode`, NPDev sees
one column dropped and another added — a destructive change — and will refuse.

Tell it instead:

```json
{ "name": "isbnCode", "renamedFrom": "isbn", "type": "string" }
```

Your data moves with the column. See `docs/DATABASES_AND_MIGRATIONS.md` for removals and other
destructive changes.

## Where to go next

- **Connect the two concepts** — a `Loan` linking Book and Member is the natural next step;
  see `docs/NPDEV_CONCEPTS_DEEP_DIVE.md` on bonds.
- **Let an AI write the model for you** — `docs/AUTHORING_WITH_AI.md` (MCP setup and the
  chat-only fallback); `docs/ai/AUTHORING_FOR_AI.md` is the underlying contract either path follows.
- **Edit in the browser** — the authoring editor lives at `/npdev-ui-react/` on your running app;
  see `docs/GETTING_STARTED.md`.
- **Go to production** — `docs/DEPLOYMENT.md` (Postgres, Docker, environment variables).
- **What NPDev deliberately does not do** — `docs/ACCEPTED_BOUNDARIES.md`.
