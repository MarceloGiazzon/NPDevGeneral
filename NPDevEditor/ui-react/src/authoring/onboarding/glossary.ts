export type GlossaryTermId =
  | "concept"
  | "reference"
  | "flow"
  | "invariant"
  | "capability"
  | "canonical-demo"
  | "official-sample"
  | "starter-template";

export type GlossaryEntry = {
  id: GlossaryTermId;
  term: string;
  definition: string;
  whyItMatters: string;
};

const GLOSSARY: Record<GlossaryTermId, GlossaryEntry> = {
  concept: {
    id: "concept",
    term: "Concept",
    definition: "A core business entity in NPDev, such as Customer, Order, CatalogItem, or ApprovalRequest.",
    whyItMatters: "Concepts anchor fields, rules, lifecycle, layout, and most of the editor experience."
  },
  reference: {
    id: "reference",
    term: "Reference",
    definition: "A field that links one concept to another and carries picker/display metadata.",
    whyItMatters: "References make relationships explicit so forms, previews, and runtime flows can stay consistent."
  },
  flow: {
    id: "flow",
    term: "Flow",
    definition: "A named business procedure made of ordered steps such as validate, call, wait, branch, or return.",
    whyItMatters: "Flows are how NPDev captures user-facing business actions beyond raw data structure."
  },
  invariant: {
    id: "invariant",
    term: "Invariant",
    definition: "A rule that must remain true for valid data or transitions.",
    whyItMatters: "Invariants protect data quality and make model rules explicit instead of hidden in code."
  },
  capability: {
    id: "capability",
    term: "Capability",
    definition: "A declared integration surface such as persistence or notification.",
    whyItMatters: "Capabilities show where the model depends on runtime behavior without hardcoding implementation details."
  },
  "canonical-demo": {
    id: "canonical-demo",
    term: "Canonical demo",
    definition: "The frozen reference specimen used as the canonical baseline.",
    whyItMatters: "It is the safest learning path because docs, tests, and generated outputs all align around it."
  },
  "official-sample": {
    id: "official-sample",
    term: "Official sample",
    definition: "A curated reference model smaller than the canonical demo and focused on one teaching scenario.",
    whyItMatters: "Official samples help users compare patterns without needing to design everything from scratch."
  },
  "starter-template": {
    id: "starter-template",
    term: "Starter template",
    definition: "A fresh model/config seed designed to reduce blank-page anxiety for new custom projects.",
    whyItMatters: "Starter templates give beginners a direction while still leaving the draft clearly theirs to evolve."
  }
};

export function listGlossaryEntries(termIds: GlossaryTermId[]): GlossaryEntry[] {
  return termIds.map((termId) => GLOSSARY[termId]);
}

