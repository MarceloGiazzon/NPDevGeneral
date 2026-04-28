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
    fields: entity.fields.map((field) => (field.name === fieldName ? updater(field) : field))
  }));
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
