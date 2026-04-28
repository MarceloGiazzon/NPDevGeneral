import type {
  AuthoringEntity,
  AuthoringField,
  AuthoringFlow,
  AuthoringLifecycleTransition,
  AuthoringModelDocument,
  AuthoringOrchestrationRule
} from "../editors/modelDocumentTypes";

export type PreviewInteractionState = {
  visible: boolean | null;
  enabled: boolean | null;
  readonly: boolean | null;
  required: boolean | null;
};

export type PreviewField = {
  name: string;
  label: string;
  type: string;
  tab: string;
  section: string;
  column: number;
  columnSpan: number;
  width: string;
  widget?: string;
  group?: string;
  placeholder?: string;
  helpText?: string;
  summaryCard?: boolean;
  listColumn?: boolean;
  listColumnOrder?: number;
  order: number;
  referenceTarget?: string;
  pickerType?: string;
  allowInlineCreate?: boolean;
  searchFields?: string[];
  filterPreset?: string;
  displayTemplate?: string;
  pickerColumns?: string[];
  previewCardTemplate?: string;
  previewFields?: string[];
  enumOptions?: Array<{
    value: string;
    label: string;
    badge?: string;
    iconHint?: string;
  }>;
  interaction: {
    visibleWhen?: string;
    enabledWhen?: string;
    readonlyWhen?: string;
    requiredWhen?: string;
  };
};

export type PreviewTab = {
  name: string;
  fields: PreviewField[];
};

export type PreviewTableColumn = {
  fieldName: string;
  label: string;
  order: number;
  width: string;
};

export type PreviewPicker = {
  fieldName: string;
  label: string;
  target: string;
  displayTemplate?: string;
  pickerColumns: string[];
  previewCardTemplate?: string;
  previewFields: string[];
  defaultFilter?: string;
  inlineCreate?: string;
};

export type PreviewAction = {
  title: string;
  kind: "flow" | "transition" | "orchestration";
  label: string;
  description: string;
  permissionHint?: string;
  confirmationText?: string;
  successMessage?: string;
  dangerLevel?: string;
  eventName?: string;
};

export type PreviewManifest = {
  entity: AuthoringEntity;
  tabs: PreviewTab[];
  tableColumns: PreviewTableColumn[];
  pickers: PreviewPicker[];
  actions: PreviewAction[];
};

function normalizeEnumOption(value: string | { value: string; label?: string; badge?: string; iconHint?: string }): {
  value: string;
  label: string;
  badge?: string;
  iconHint?: string;
} {
  if (typeof value === "string") {
    return {
      value,
      label: value
    };
  }
  return {
    value: value.value,
    label: value.label ?? value.value,
    badge: value.badge,
    iconHint: value.iconHint
  };
}

function normalizeField(field: AuthoringField): PreviewField {
  return {
    name: field.name,
    label: field.ui?.label ?? field.name,
    type: field.type ?? "string",
    tab: field.ui?.tab ?? "Details",
    section: field.ui?.section ?? field.ui?.group ?? "General",
    column: field.ui?.column ?? 1,
    columnSpan: field.ui?.columnSpan ?? 1,
    width: field.ui?.width ?? "md",
    widget: field.ui?.widget,
    group: field.ui?.group,
    placeholder: field.ui?.placeholder,
    helpText: field.ui?.helpText,
    summaryCard: field.ui?.summaryCard,
    listColumn: field.ui?.listColumn,
    listColumnOrder: field.ui?.listColumnOrder,
    order: field.ui?.order ?? 999,
    referenceTarget: field.reference?.target,
    pickerType: field.ui?.pickerType,
    allowInlineCreate: field.ui?.allowInlineCreate,
    searchFields: field.reference?.searchFields ?? field.ui?.searchFields,
    filterPreset: field.reference?.defaultFilter ?? field.ui?.filterPreset,
    displayTemplate: field.reference?.displayTemplate,
    pickerColumns: field.reference?.pickerColumns,
    previewCardTemplate: field.reference?.previewCardTemplate,
    previewFields: field.reference?.previewFields,
    enumOptions: (field.enumValues ?? []).map(normalizeEnumOption),
    interaction: {
      visibleWhen: field.ui?.visibleWhen,
      enabledWhen: field.ui?.enabledWhen,
      readonlyWhen: field.ui?.readonlyWhen,
      requiredWhen: field.ui?.requiredWhen
    }
  };
}

function buildActionPreview(
  flows: AuthoringFlow[],
  transitions: AuthoringLifecycleTransition[],
  orchestrationRules: AuthoringOrchestrationRule[]
): PreviewAction[] {
  const flowActions: PreviewAction[] = flows.map((flow) => ({
    title: flow.name,
    kind: "flow",
    label: flow.action?.label ?? flow.name,
    description: flow.action?.failureHint ?? "Flow-triggered action preview",
    permissionHint: flow.action?.permissionHint,
    confirmationText: flow.action?.confirmationText,
    successMessage: flow.action?.successMessage,
    dangerLevel: flow.action?.dangerLevel
  }));

  const transitionActions: PreviewAction[] = transitions.map((transition) => ({
    title: `${transition.from} -> ${transition.to}`,
    kind: "transition",
    label: transition.action?.label ?? transition.actionLabel ?? `${transition.from} -> ${transition.to}`,
    description: transition.action?.failureHint ?? "Lifecycle transition action preview",
    permissionHint: transition.action?.permissionHint,
    confirmationText: transition.action?.confirmationText,
    successMessage: transition.action?.successMessage,
    dangerLevel: transition.action?.dangerLevel,
    eventName: transition.event
  }));

  const orchestrationActions: PreviewAction[] = orchestrationRules.flatMap((rule) =>
    (rule.actions ?? []).map((action) => ({
      title: `${rule.name} / ${action.type}`,
      kind: "orchestration",
      label: action.action?.label ?? `${rule.name} ${action.type}`,
      description: action.action?.failureHint ?? "Orchestration action preview",
      permissionHint: action.action?.permissionHint,
      confirmationText: action.action?.confirmationText,
      successMessage: action.action?.successMessage,
      dangerLevel: action.action?.dangerLevel,
      eventName: rule.trigger?.event
    }))
  );

  return [...flowActions, ...transitionActions, ...orchestrationActions];
}

export function buildPreviewManifest(
  document: AuthoringModelDocument,
  conceptName?: string | null
): PreviewManifest | null {
  const entity = document.concepts.find((entry) => entry.name === conceptName) ?? document.concepts[0];
  if (!entity) {
    return null;
  }

  const previewFields = entity.fields.map(normalizeField).sort((left, right) => left.order - right.order);
  const tabs = Array.from(
    previewFields.reduce<Map<string, PreviewField[]>>((map, field) => {
      map.set(field.tab, [...(map.get(field.tab) ?? []), field]);
      return map;
    }, new Map())
  ).map(([name, fields]) => ({
    name,
    fields: fields.sort((left, right) => left.order - right.order)
  }));

  const tableColumns = previewFields
    .filter((field) => field.listColumn)
    .map((field) => ({
      fieldName: field.name,
      label: field.label,
      order: field.listColumnOrder ?? field.order,
      width: field.width
    }))
    .sort((left, right) => left.order - right.order);

  const pickers = previewFields
    .filter((field) => field.type === "reference" && field.referenceTarget)
    .map((field) => ({
      fieldName: field.name,
      label: field.label,
      target: field.referenceTarget ?? "",
      displayTemplate: field.displayTemplate,
      pickerColumns: field.pickerColumns ?? [],
      previewCardTemplate: field.previewCardTemplate,
      previewFields: field.previewFields ?? [],
      defaultFilter: field.filterPreset,
      inlineCreate: field.allowInlineCreate ? "allow" : field.referenceTarget ? "deny" : undefined
    }));

  const actions = buildActionPreview(document.flows ?? [], entity.lifecycle?.transitions ?? [], document.orchestrationRules ?? []);

  return {
    entity,
    tabs,
    tableColumns,
    pickers,
    actions
  };
}

function parseLiteral(rawValue: string): string | boolean | null | number {
  const trimmed = rawValue.trim();
  if (trimmed === "null") {
    return null;
  }
  if (trimmed === "true") {
    return true;
  }
  if (trimmed === "false") {
    return false;
  }
  if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith('"') && trimmed.endsWith('"'))) {
    return trimmed.slice(1, -1);
  }
  const numericValue = Number(trimmed);
  return Number.isFinite(numericValue) ? numericValue : trimmed;
}

function evaluateClause(expression: string, context: Record<string, unknown>): boolean | null {
  const match = expression.trim().match(/^([a-zA-Z_][a-zA-Z0-9_]*)\s*(==|!=)\s*(.+)$/);
  if (!match) {
    return null;
  }
  const [, fieldName, operator, rawValue] = match;
  const expectedValue = parseLiteral(rawValue);
  const currentValue = context[fieldName];
  return operator === "==" ? currentValue === expectedValue : currentValue !== expectedValue;
}

export function evaluatePreviewExpression(
  expression: string | undefined,
  context: Record<string, unknown>
): boolean | null {
  if (!expression) {
    return null;
  }

  const orParts = expression.split("||").map((entry) => entry.trim()).filter(Boolean);
  if (orParts.length > 1) {
    let hasKnown = false;
    for (const part of orParts) {
      const value = evaluatePreviewExpression(part, context);
      if (value === true) {
        return true;
      }
      if (value !== null) {
        hasKnown = true;
      }
    }
    return hasKnown ? false : null;
  }

  const andParts = expression.split("&&").map((entry) => entry.trim()).filter(Boolean);
  if (andParts.length > 1) {
    let hasKnown = false;
    for (const part of andParts) {
      const value = evaluatePreviewExpression(part, context);
      if (value === false) {
        return false;
      }
      if (value !== null) {
        hasKnown = true;
      }
    }
    return hasKnown ? true : null;
  }

  return evaluateClause(expression, context);
}

function initialFieldValue(field: AuthoringField): unknown {
  if (field.default !== undefined) {
    return field.default;
  }
  if (field.type === "enum") {
    const options = field.enumValues ?? [];
    const defaultOption = options.find((option) => typeof option !== "string" && option.default);
    if (defaultOption && typeof defaultOption !== "string") {
      return defaultOption.value;
    }
    if (options[0]) {
      return typeof options[0] === "string" ? options[0] : options[0].value;
    }
  }
  if (field.type === "boolean") {
    return false;
  }
  return null;
}

export function buildInitialPreviewContext(entity: AuthoringEntity): Record<string, unknown> {
  return Object.fromEntries(entity.fields.map((field) => [field.name, initialFieldValue(field)]));
}

export function resolveFieldInteractionState(
  field: PreviewField,
  context: Record<string, unknown>
): PreviewInteractionState {
  return {
    visible: field.interaction.visibleWhen ? evaluatePreviewExpression(field.interaction.visibleWhen, context) : true,
    enabled: field.interaction.enabledWhen ? evaluatePreviewExpression(field.interaction.enabledWhen, context) : true,
    readonly: field.interaction.readonlyWhen ? evaluatePreviewExpression(field.interaction.readonlyWhen, context) : false,
    required: field.interaction.requiredWhen ? evaluatePreviewExpression(field.interaction.requiredWhen, context) : false
  };
}
