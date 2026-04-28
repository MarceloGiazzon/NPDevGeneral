export type UiPresentationMetadata = {
  label?: string;
  shortLabel?: string;
  description?: string;
  helpText?: string;
  placeholder?: string;
  widget?: string;
  group?: string;
  section?: string;
  order?: number;
  advanced?: boolean;
  deprecated?: boolean;
  examples?: string[];
  visibleWhen?: string;
  enabledWhen?: string;
  readonlyWhen?: string;
  requiredWhen?: string;
  pickerType?: string;
  allowInlineCreate?: boolean;
  searchFields?: string[];
  filterPreset?: string;
  tab?: string;
  column?: number;
  columnSpan?: number;
  width?: string;
  summaryCard?: boolean;
  listColumn?: boolean;
  listColumnOrder?: number;
  formColumns?: number;
  displayMode?: string;
  defaultSort?: string;
  defaultGroup?: string;
};

export type UiActionMetadata = {
  label?: string;
  confirmationText?: string;
  successMessage?: string;
  failureHint?: string;
  dangerLevel?: "low" | "medium" | "high" | "critical";
  visibleWhen?: string;
  permissionHint?: string;
  inputFormHint?: string;
};

export type UiModelField = {
  name: string;
  type?: string;
  domainType?: string;
  description?: string;
  default?: unknown;
  defaultExpression?: string;
  derivedExpression?: string;
  properties?: Record<string, UiModelSchema>;
  items?: UiModelSchema;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  itemIdentityField?: string;
  duplicationPolicy?: "allow" | "deny";
  enumValues?: string[];
  enumOptions?: UiModelEnumOption[];
  ref?: string;
  reference?: string | UiModelReferenceSemantics;
  referenceTarget?: string;
  referenceSemantics?: UiModelReferenceSemantics;
  ui?: UiPresentationMetadata;
};

export type UiModelEnumOption = {
  value: string;
  label?: string;
  order?: number;
  group?: string;
  default?: boolean;
  deprecated?: boolean;
  iconHint?: string;
  badgeHint?: string;
  description?: string;
};

export type UiModelSchema = {
  type?: string;
  description?: string;
  default?: unknown;
  defaultExpression?: string;
  derivedExpression?: string;
  properties?: Record<string, UiModelSchema>;
  items?: UiModelSchema;
  required?: string[];
  enumValues?: string[];
  minLength?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  itemIdentityField?: string;
  duplicationPolicy?: "allow" | "deny";
};

export type UiModelReferenceSemantics = {
  target: string;
  multiple?: boolean;
  displayField?: string;
  searchFields?: string[];
  previewFields?: string[];
  inlineCreate?: "allow" | "deny";
  displayTemplate?: string;
  pickerColumns?: string[];
  previewCardTemplate?: string;
  defaultFilter?: string;
};

export type UiModelDomainType = {
  name: string;
  baseType: string;
  format?: string;
  normalization?: string[];
  examples?: string[];
  validation?: {
    type?: string;
    minLength?: number;
    maxLength?: number;
    min?: number;
    max?: number;
    regex?: string;
    description?: string;
  };
  ui?: UiPresentationMetadata;
};

export type UiModelConcept = {
  name: string;
  ui?: UiPresentationMetadata;
  fields?: UiModelField[];
};

export type UiModelResponse = {
  namespace?: string;
  dslVersion?: string;
  version?: string;
  domainTypes?: UiModelDomainType[];
  concepts?: UiModelConcept[];
};

export type ValidationLayer = "structural" | "semantic" | "ux-metadata";

export type ValidationSeverity = "error" | "warning" | "info";

export type ValidationDiagnostic = {
  layer: ValidationLayer;
  severity: ValidationSeverity;
  code: string;
  message: string;
  sourceModule: string;
  path?: string;
  concept?: string;
  field?: string;
  section?: string;
  ruleName?: string;
  suggestedFix?: string;
  helpKey?: string;
};

export type ValidationReport = {
  contract: "validation-diagnostic-v1";
  diagnostics: ValidationDiagnostic[];
};

export type RuntimeEventItem = {
  eventType?: string;
  entityId?: string;
  correlationId?: string;
  occurredAt?: string;
  payload?: unknown;
};

export type AuditRecordItem = {
  recordType?: string;
  message?: string;
  summary?: string;
  occurredAt?: string;
  correlationId?: string;
};

export type CorrelationTimelineItem = {
  kind?: string;
  summary?: string;
  message?: string;
  occurredAt?: string;
};

export type ExecutionSummaryResponse = {
  executionId: string;
  correlationId?: string;
  flowName?: string;
  status?: string;
  currentStepIndex?: number | null;
  waitingForEventName?: string | null;
  updatedAtEpochMs?: number;
  resumeAttemptCount?: number | null;
  lastResumeAtEpochMs?: number | null;
  lastResumeErrorCode?: string | null;
  nextEligibleResumeAtEpochMs?: number | null;
  lastProgressAtEpochMs?: number | null;
  lastErrorKind?: string | null;
  lastErrorCode?: string | null;
  failedAtEpochMs?: number | null;
};

export type EventMetaSummaryResponse = {
  eventId: string;
  tenantId?: string | null;
  correlationId?: string | null;
  eventName?: string | null;
  flowName?: string | null;
  stepIndex?: number;
  timestampMs?: number;
};

export type TraceSummaryResponse = {
  executionId: string;
  correlationId?: string | null;
  flowName?: string | null;
  outcome?: string | null;
  startedAtEpochMs?: number;
  endedAtEpochMs?: number;
};

export type CorrelationTimelineResponse = {
  correlationId?: string | null;
  executions: ExecutionSummaryResponse[];
  events: EventMetaSummaryResponse[];
  traceExecutionIds: string[];
};

export type ModelEditorFieldDraft = {
  name: string;
  type?: string;
  domainType?: string;
  description?: string;
  default?: unknown;
  defaultExpression?: string;
  derivedExpression?: string;
  properties?: Record<string, UiModelSchema>;
  items?: UiModelSchema;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  itemIdentityField?: string;
  duplicationPolicy?: "allow" | "deny";
  ref?: string | null;
  reference?: UiModelReferenceSemantics | string | null;
  referenceTarget?: string | null;
  referenceSemantics?: UiModelReferenceSemantics | null;
  enumValues?: string[];
  enumOptions?: UiModelEnumOption[];
  ui?: UiPresentationMetadata;
};

export type ModelEditorEntityDraft = {
  name: string;
  ui?: UiPresentationMetadata;
  fields: ModelEditorFieldDraft[];
  lifecycle?: {
    statusField?: string;
    states?: Array<{
      value: string;
      label?: string;
      initial?: boolean;
      terminal?: boolean;
      metadata?: Record<string, string>;
    }>;
    transitions?: Array<{
      from: string;
      to: string;
      requiredPayload?: string[];
      guard?: string;
      event?: string;
      actionLabel?: string;
      action?: UiActionMetadata;
      metadata?: Record<string, string>;
    }>;
  };
};

export type ModelEditorDraft = {
  namespace: string;
  dslVersion?: string;
  version: string;
  domainTypes?: UiModelDomainType[];
  entities: ModelEditorEntityDraft[];
};

export type RuleEditorInvariantDraft = {
  name: string;
  expression: string;
  message: string;
};

export type RuleEditorTransitionRuleDraft = {
  from: string;
  to: string;
  requires: string[];
  guard?: string;
  event?: string;
  actionLabel?: string;
  action?: UiActionMetadata;
  metadata?: Record<string, string>;
  message: string;
};

export type RuleEditorOrchestrationRuleDraft = {
  name: string;
  event: string;
  condition: string;
  action: string;
};

export type RuleEditorEntityRulesDraft = {
  entityName: string;
  invariantPalette: RuleEditorInvariantDraft[];
  stateTransitionRules: RuleEditorTransitionRuleDraft[];
  orchestrationTriggerRules: RuleEditorOrchestrationRuleDraft[];
};

export type RuleEditorDraft = {
  namespace: string;
  version: string;
  entities: RuleEditorEntityRulesDraft[];
};

export type PluginPackageSummary = {
  id?: string;
  status?: string;
  source?: string;
};

export type PluginExecutionSummary = {
  id?: string;
  status?: string;
  startedAt?: string;
};

export type ScheduleSummary = {
  id?: string;
  status?: string;
  eventType?: string;
};

export type RuntimeRefreshStatus = {
  status?: string;
  restartRequired?: boolean;
  projectionRefresh?: string;
};
