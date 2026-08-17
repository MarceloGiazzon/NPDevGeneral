import { describe, expect, it } from "vitest";
import { buildSemanticDiff } from "./semanticDiff";
import type { AuthoringBundle } from "../io/bundleTypes";
import type { AuthoringEntity, AuthoringModelDocument } from "../editors/modelDocumentTypes";
import type { AuthoringConfigDocument } from "../config/configDocumentTypes";

/**
 * Regression for the duplicate-React-key bug seen live in the Import/Export screen: two freshly
 * added draft concepts with no name yet (name is `undefined` at runtime despite the `string` type)
 * both produced path "concepts.undefined" for an "added" change, so SemanticDiffPanel rendered two
 * <article key="concepts.undefined-added"> siblings and React silently dropped one.
 */
function emptyModel(concepts: AuthoringEntity[]): AuthoringModelDocument {
  return {
    namespace: "sample",
    version: "1.0.0",
    concepts,
    flows: []
  } as unknown as AuthoringModelDocument;
}

function emptyConfig(): AuthoringConfigDocument {
  return {
    scenario: { name: "sample", outputRoot: "Output" },
    runtime: { springProfile: "dev", serverPort: 8080 },
    database: { database: "h2", provider: "h2" },
    bootstrap: { mergeStrategy: "replace" }
  } as unknown as AuthoringConfigDocument;
}

describe("buildSemanticDiff", () => {
  it("gives every added change a distinct path even when several concepts share the same (or missing) name", () => {
    const before = emptyModel([]);
    const after = emptyModel([
      { name: undefined as unknown as string, fields: [] },
      { name: undefined as unknown as string, fields: [] }
    ]);

    const bundleBefore: AuthoringBundle = { model: before, config: emptyConfig() };
    const bundleAfter: AuthoringBundle = { model: after, config: emptyConfig() };

    const summaries = buildSemanticDiff(bundleBefore, bundleAfter);
    const conceptChanges = summaries.find((summary) => summary.title === "Concept changes")!;

    expect(conceptChanges.changes).toHaveLength(2);
    const paths = conceptChanges.changes.map((change) => change.path);
    expect(new Set(paths).size).toBe(paths.length);
  });
});
