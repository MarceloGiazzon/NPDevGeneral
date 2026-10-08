# Rollup fields

A rollup is a **stored** field on a concept that the platform keeps equal to an aggregate over a
child concept's rows: a like counter, an order total, the latest bid. You declare it; you never
write code that keeps it in step.

```json
{
  "name": "Mosaic",
  "fields": [
    { "name": "id", "type": "uuid", "id": true, "required": true },
    { "name": "title", "type": "string" },
    { "name": "likeCount", "type": "integer" }
  ],
  "rollups": [
    { "field": "likeCount", "from": "Like", "via": "mosaicId", "fn": "count" }
  ]
}
```

| Key | Meaning |
|---|---|
| `field` | A numeric field (`int`/`integer`/`long`/`decimal`) of this concept. It holds the value. |
| `from` | The child concept whose rows are aggregated. |
| `via` | The child's `reference` field that points at this concept. |
| `fn` | `count` (default), `sum`, `min`, `max` or `avg`. |
| `of` | The child's numeric field that `sum`/`min`/`max`/`avg` read. `count` takes none. |

`count` and `sum` over no rows are `0`; `min`/`max`/`avg` over no rows are `null`.

## When it is recomputed

Every write to a `ConceptStore` passes through the RuntimeHost's `RollupConceptStoreDecorator`.
That covers generated CRUD, flow `createConcept`/`patchConcept` steps, aggregate commits and agent
writes. Within the same transaction it:

- **after a child is saved, deleted or restored**, recomputes the parent the child points at. If
  the save moved the child to another parent, it recomputes the old parent too;
- **when the parent itself is saved**, overwrites the rollup fields with freshly computed values, so
  a client can never set them and a stale edit form can never roll them back.

The parent row is locked (`SELECT ... FOR UPDATE` on JDBC) before it is recounted, so concurrent
child writes serialize on it and none is lost. The value is written with a narrow `UPDATE` that
does **not** bump the parent's row version, so a like landing on a Mosaic never turns its owner's
open edit into an [optimistic-lock conflict](OPTIMISTIC_LOCKING.md).

Because the value is an ordinary column, it sorts, filters and pages like any other field:

```json
{ "name": "MostLikedMosaics", "concept": "Mosaic", "where": "status != 'DRAFT'",
  "orderBy": ["likeCount desc", "title"], "limit": 20 }
```

## Rules the validator enforces

- `field` exists on this concept, is numeric and is not the id; one rollup per field.
- `from` is a known concept; `via` is one of its `reference` fields targeting this concept.
- `fn` is one of the five functions; non-`count` functions name a numeric `of` field on `from`;
  `count` names none.

## Limits

- One hop only: a rollup reads its direct children, not grandchildren. A rollup over another
  rollup field works, because the child's own rollup is written before its parent is recounted.
- No `where` filter yet (e.g. "count only PUBLISHED children"). Use a query with a `where` for
  that view today.
- Rows that existed before the rollup was declared are counted on the next write that touches
  the parent or one of its children, not at boot.
- Like a `derivedExpression` field, a rollup field still appears as an input in generated forms.
  The platform overwrites whatever is submitted. The typed `PUT`/`POST` response still echoes the
  submitted value; the next read returns the real one.

Worked examples: `NPDevSamples/pigmentampas` (`Mosaic.likeCount`) and
`NPDevSamples/dsl-conformance-max` (`WidgetCatalogEntry.slotCount` / `highestSlotRow`).
