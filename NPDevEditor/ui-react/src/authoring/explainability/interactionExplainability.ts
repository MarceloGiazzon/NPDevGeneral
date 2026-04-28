import type { AuthoringEntity } from "../editors/modelDocumentTypes";

export type ExplainabilityEntry = {
  title: string;
  kind: "visibility" | "enablement" | "readonly" | "required";
  targetPath: string;
  summary: string;
  expression: string;
};

function pushIfExpression(
  entries: ExplainabilityEntry[],
  entity: AuthoringEntity,
  fieldName: string,
  kind: ExplainabilityEntry["kind"],
  expression: string | undefined,
  summary: string
): void {
  if (!expression) {
    return;
  }
  entries.push({
    title: `${entity.name}.${fieldName}`,
    kind,
    targetPath: `${entity.name}.${fieldName}`,
    summary,
    expression
  });
}

export function buildFieldExplainabilityEntries(entity: AuthoringEntity | null): ExplainabilityEntry[] {
  if (!entity) {
    return [];
  }

  const entries: ExplainabilityEntry[] = [];
  for (const field of entity.fields) {
    pushIfExpression(
      entries,
      entity,
      field.name,
      "visibility",
      field.ui?.visibleWhen,
      "This field is conditionally visible based on interaction metadata."
    );
    pushIfExpression(
      entries,
      entity,
      field.name,
      "enablement",
      field.ui?.enabledWhen,
      "This field becomes editable only when the condition is satisfied."
    );
    pushIfExpression(
      entries,
      entity,
      field.name,
      "readonly",
      field.ui?.readonlyWhen,
      "This field becomes read-only when the condition is satisfied."
    );
    pushIfExpression(
      entries,
      entity,
      field.name,
      "required",
      field.ui?.requiredWhen,
      "This field becomes required when the condition is satisfied."
    );
  }

  return entries;
}
