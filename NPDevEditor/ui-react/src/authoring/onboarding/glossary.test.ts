import { describe, expect, it } from "vitest";
import { listGlossaryEntries, type GlossaryTermId } from "./glossary";

const ALL_TERM_IDS: GlossaryTermId[] = [
  "concept",
  "reference",
  "flow",
  "invariant",
  "capability",
  "canonical-demo",
  "official-sample",
  "starter-template"
];

describe("listGlossaryEntries", () => {
  it("returns a fully-populated entry for every known term id, in the requested order", () => {
    const entries = listGlossaryEntries(ALL_TERM_IDS);

    expect(entries).toHaveLength(ALL_TERM_IDS.length);
    entries.forEach((entry, index) => {
      expect(entry.id).toBe(ALL_TERM_IDS[index]);
      expect(entry.term.length).toBeGreaterThan(0);
      expect(entry.definition.length).toBeGreaterThan(0);
      expect(entry.whyItMatters.length).toBeGreaterThan(0);
    });
  });

  it("preserves duplicates and a caller-chosen subset order rather than a fixed catalog order", () => {
    const entries = listGlossaryEntries(["flow", "concept", "flow"]);

    expect(entries.map((entry) => entry.id)).toEqual(["flow", "concept", "flow"]);
  });

  it("returns an empty list for an empty request without touching the catalog", () => {
    expect(listGlossaryEntries([])).toEqual([]);
  });
});
