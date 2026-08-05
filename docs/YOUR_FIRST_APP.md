# Your first app

You have run the demo. Now build something of your own.

This takes about 15 minutes and produces a working application — database, REST API, admin
screens, login — from a file you can read.

## What you are actually building

An NPDev app is described by one file: `model.json`. Everything else is generated from it.

**That file is your application.** The generated code is disposable — you can delete and
regenerate it any time. The model is the part worth keeping, backing up, and putting in git.

We will build a small library: **Books** and the **Members** who borrow them.

## 1. Start from a copy

Copy the demo's config to a folder of your own, outside the NPDev repo:

```sh
mkdir -p ../my-library
cp NPDevContract/dsl/resources/Models/canonical-demo/config.json ../my-library/config.json
```

## 2. Write your model

Create `model.json` in that same folder:

```json
{
  "$schema": "../NPDevGeneral/NPDevContract/schemas/model.schema.json",
  "namespace": "library",
  "dslVersion": "1.0.0",
  "version": "0.1.0",
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
}
```

**Start from a blank model, not a trimmed copy of the demo.** The demo also declares
`domainTypes`/`capabilities`/`bindings`/`events`/`orchestrationRules`/`flows` that reference its
own Patient/Encounter concepts — copy those across without removing them and validation fails with
"references unknown concept" errors that have nothing to do with anything you changed. Two
concepts and nothing else is the actual smallest valid model.

## 3. Give it a real, persistent database

Also create `db.definition.json` next to it:

```json
{
  "database": { "engine": "H2Local", "databaseName": "my_library", "username": "sa", "password": "", "createInternalTables": true, "createBusinessTables": true },
  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "allowDestructiveRecreate": false, "destructiveRecreateConfirmation": "", "scope": "NpdevOwnedTablesOnly" }
}
```

**Without this file, `generate app` silently defaults to an in-memory database that forgets
everything the moment the app stops** — fine for a five-minute demo, wrong for the point of step 6
below, which is proving your data survives a schema change.

## 4. Give it a history

```sh
cd ../my-library
git init && git add . && git commit -m "start from the NPDev demo config"
```

**Now, not after you've made changes.** Your model is the app; give it a history from the first
commit, or the very first version of it — the one you'd most want back if you broke something —
is the one version that was never saved.

## 5. Check it before you build it

```sh
cd ../NPDevGeneral
./npdev validate model ../my-library/model.json
```

Expect `"status": "passed"`.

**If it fails, read the `message` and `suggestedFix` on each diagnostic** — they name the exact
path in your file. Fix and re-run. Getting errors here is normal and cheap; getting them after a
five-minute build is not.

## 6. Build and run it

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

## 7. Change it and watch it follow

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

## 8. Renaming — read this before you need it

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
- **Let an AI write the model for you** — `docs/ai/AUTHORING_FOR_AI.md`.
- **Edit in the browser** — the authoring editor lives at `/npdev-ui-react/` on your running app;
  see `docs/GETTING_STARTED.md`.
- **Go to production** — `docs/DEPLOYMENT.md` (Postgres, Docker, environment variables).
- **What NPDev deliberately does not do** — `docs/ACCEPTED_BOUNDARIES.md`.
