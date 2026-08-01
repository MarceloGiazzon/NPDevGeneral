import type {
  AuthoringEntity,
  AuthoringEnumOption,
  AuthoringField,
  AuthoringModelDocument,
  AuthoringSchemaProperty
} from "./modelDocumentTypes";

export function cloneDocument(document: AuthoringModelDocument): AuthoringModelDocument {
  return JSON.parse(JSON.stringify(document)) as AuthoringModelDocument;
}

export function moveItem<T>(items: T[], index: number, direction: -1 | 1): T[] {
  const targetIndex = index + direction;
  if (targetIndex < 0 || targetIndex >= items.length) {
    return items;
  }
  const next = items.slice();
  const [item] = next.splice(index, 1);
  next.splice(targetIndex, 0, item);
  return next;
}

export function enumOptionFromValue(value: string | AuthoringEnumOption): AuthoringEnumOption {
  if (typeof value === "string") {
    return {
      value,
      label: value
    };
  }
  return value;
}

export function updateEntity(
  document: AuthoringModelDocument,
  entityName: string,
  updater: (entity: AuthoringEntity) => AuthoringEntity
): AuthoringModelDocument {
  return {
    ...document,
    concepts: document.concepts.map((entity) => (entity.name === entityName ? updater(entity) : entity))
  };
}

export function updateField(
  document: AuthoringModelDocument,
  entityName: string,
  fieldName: string,
  updater: (field: AuthoringField) => AuthoringField
): AuthoringModelDocument {
  return updateEntity(document, entityName, (entity) => ({
    ...entity,
    fields: entity.fields.map((field) => (field.name === fieldName ? applyFieldUpdate(field, updater) : field))
  }));
}

/**
 * Move 9 B2 (docs/ACCEPTED_BOUNDARIES.md B1): stamps `renamedFrom` automatically whenever the
 * updater changes a field's `name`. The "Field name" input in `FieldDetailsEditor.tsx` is the only
 * caller that ever changes `name` (adding a new field goes through a separate path that never calls
 * `updateField`), so this is the single choke point every rename passes through -- the DSL/migration
 * engine only sees a real rename when the author actually renamed something in the editor, never a
 * guess.
 *
 * Preserves the ORIGINAL pre-rename name across multiple renames within one session (does not
 * overwrite an existing `renamedFrom` with an intermediate name), and clears it if the field is
 * renamed back to that original name -- a name that nets out unchanged should not be recorded as a
 * rename.
 */
function applyFieldUpdate(field: AuthoringField, updater: (field: AuthoringField) => AuthoringField): AuthoringField {
  const updated = updater(field);
  if (updated.name === field.name) {
    return updated;
  }
  const originalName = field.renamedFrom ?? field.name;
  if (updated.name === originalName) {
    const { renamedFrom: _renamedFrom, ...withoutRenamedFrom } = updated;
    return withoutRenamedFrom;
  }
  return { ...updated, renamedFrom: originalName };
}

export function ensureObjectProperties(field: AuthoringField): Record<string, AuthoringSchemaProperty> {
  return field.properties ?? {};
}

export function ensureArrayItemProperties(field: AuthoringField): Record<string, AuthoringSchemaProperty> {
  if (field.items?.properties) {
    return field.items.properties;
  }
  return {};
}

export function buildEmptyProperty(name = "property"): [string, AuthoringSchemaProperty] {
  return [
    name,
    {
      type: "string"
    }
  ];
}

export function prettyDocumentJson(document: AuthoringModelDocument): string {
  return JSON.stringify(document, null, 2);
}

export function joinTextList(values: string[] | undefined): string {
  return (values ?? []).join(", ");
}

export function parseTextList(value: string): string[] {
  return value
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
}
