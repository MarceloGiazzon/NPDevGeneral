import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import type { AuthoringModelDocument } from "../editors/modelDocumentTypes";
import type { AuthoringBundle, SemanticDiffChange, SemanticDiffSummary } from "../io/bundleTypes";

/**
 * Disambiguates repeated names (including several unnamed-yet draft rows, which all carry
 * `undefined`) so every change gets a distinct path -- without this, two unnamed concepts both
 * produce path "concepts.undefined", which collide as React keys in SemanticDiffPanel and cause
 * one of the two changes to be silently dropped from the rendered list.
 */
function uniquePath(pathPrefix: string, value: string, seen: Map<string, number>): string {
  const label = value ?? "(unnamed)";
  const occurrence = seen.get(label) ?? 0;
  seen.set(label, occurrence + 1);
  return occurrence === 0 ? `${pathPrefix}.${label}` : `${pathPrefix}.${label}#${occurrence + 1}`;
}

function appendNameDiffs(
  title: string,
  pathPrefix: string,
  beforeValues: string[],
  afterValues: string[]
): SemanticDiffSummary {
  const beforeSet = new Set(beforeValues);
  const afterSet = new Set(afterValues);
  const changes: SemanticDiffChange[] = [];

  const addedSeen = new Map<string, number>();
  for (const value of afterValues) {
    if (!beforeSet.has(value)) {
      changes.push({
        kind: "added",
        path: uniquePath(pathPrefix, value, addedSeen),
        after: value
      });
    }
  }

  const removedSeen = new Map<string, number>();
  for (const value of beforeValues) {
    if (!afterSet.has(value)) {
      changes.push({
        kind: "removed",
        path: uniquePath(pathPrefix, value, removedSeen),
        before: value
      });
    }
  }

  return { title, changes };
}

function summarizeModelDiff(before: AuthoringModelDocument, after: AuthoringModelDocument): SemanticDiffSummary[] {
  const summaries: SemanticDiffSummary[] = [];
  summaries.push(
    appendNameDiffs(
      "Concept changes",
      "concepts",
      before.concepts.map((entity) => entity.name),
      after.concepts.map((entity) => entity.name)
    )
  );
  summaries.push(
    appendNameDiffs(
      "Flow changes",
      "flows",
      (before.flows ?? []).map((flow) => flow.name),
      (after.flows ?? []).map((flow) => flow.name)
    )
  );

  const fieldChanges: SemanticDiffChange[] = [];
  for (const afterEntity of after.concepts) {
    const beforeEntity = before.concepts.find((entity) => entity.name === afterEntity.name);
    if (!beforeEntity) {
      continue;
    }
    const beforeFields = beforeEntity.fields.map((field) => field.name);
    const afterFields = afterEntity.fields.map((field) => field.name);
    fieldChanges.push(
      ...appendNameDiffs(
        `${afterEntity.name} field changes`,
        `concepts.${afterEntity.name}.fields`,
        beforeFields,
        afterFields
      ).changes
    );
  }
  summaries.push({
    title: "Field changes",
    changes: fieldChanges
  });

  const scalarChanges: SemanticDiffChange[] = [];
  if (before.namespace !== after.namespace) {
    scalarChanges.push({
      kind: "changed",
      path: "namespace",
      before: before.namespace,
      after: after.namespace
    });
  }
  if (before.version !== after.version) {
    scalarChanges.push({
      kind: "changed",
      path: "version",
      before: before.version,
      after: after.version
    });
  }
  summaries.push({
    title: "Model identity changes",
    changes: scalarChanges
  });

  return summaries;
}

function summarizeConfigDiff(before: AuthoringConfigDocument, after: AuthoringConfigDocument): SemanticDiffSummary[] {
  const changes: SemanticDiffChange[] = [];

  const scalarPairs: Array<[string, string | number | boolean | undefined, string | number | boolean | undefined]> = [
    ["scenario.name", before.scenario.name, after.scenario.name],
    ["scenario.outputRoot", before.scenario.outputRoot, after.scenario.outputRoot],
    ["runtime.springProfile", before.runtime.springProfile, after.runtime.springProfile],
    ["runtime.serverPort", before.runtime.serverPort, after.runtime.serverPort],
    ["database.database", before.database.database, after.database.database],
    ["database.provider", before.database.provider, after.database.provider],
    ["bootstrap.mergeStrategy", before.bootstrap.mergeStrategy, after.bootstrap.mergeStrategy]
  ];

  for (const [path, beforeValue, afterValue] of scalarPairs) {
    if (beforeValue !== afterValue) {
      changes.push({
        kind: "changed",
        path,
        before: beforeValue == null ? undefined : String(beforeValue),
        after: afterValue == null ? undefined : String(afterValue)
      });
    }
  }

  return [
    {
      title: "Config changes",
      changes
    }
  ];
}

export function buildSemanticDiff(before: AuthoringBundle, after: AuthoringBundle): SemanticDiffSummary[] {
  return [...summarizeModelDiff(before.model, after.model), ...summarizeConfigDiff(before.config, after.config)];
}
