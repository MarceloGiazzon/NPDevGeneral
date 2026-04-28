import type { UiActionMetadata, UiPresentationMetadata } from "../../types";

export type AuthoringModelPrimitive =
  | string
  | number
  | boolean
  | null
  | AuthoringModelPrimitive[]
  | { [key: string]: AuthoringModelPrimitive };

export type AuthoringValidationSpec = {
  type?: string;
  minLength?: number;
  maxLength?: number;
  min?: number;
  max?: number;
  regex?: string;
  description?: string;
};

export type AuthoringSchemaProperty = {
  type?: string;
  description?: string;
  default?: AuthoringModelPrimitive;
  enumValues?: Array<string | AuthoringEnumOption>;
  properties?: Record<string, AuthoringSchemaProperty>;
  items?: AuthoringSchemaProperty;
  required?: string[];
  minItems?: number;
  maxItems?: number;
};

export type AuthoringDomainType = {
  name: string;
  baseType: string;
  validation?: AuthoringValidationSpec;
  normalization?: string[];
  format?: string;
  examples?: string[];
  ui?: UiPresentationMetadata;
};

export type AuthoringEnumOption = {
  value: string;
  label?: string;
  order?: number;
  group?: string;
  default?: boolean;
  deprecated?: boolean;
  iconHint?: string;
  badge?: string;
  description?: string;
};

export type AuthoringReferenceSemantics = {
  target: string;
  displayField?: string;
  displayTemplate?: string;
  searchFields?: string[];
  pickerColumns?: string[];
  previewFields?: string[];
  previewCardTemplate?: string;
  defaultFilter?: string;
  inlineCreate?: "allow" | "deny";
};

export type AuthoringField = {
  name: string;
  type?: string;
  id?: boolean;
  required?: boolean;
  description?: string;
  domainType?: string;
  default?: AuthoringModelPrimitive;
  defaultExpression?: string;
  derivedExpression?: string;
  properties?: Record<string, AuthoringSchemaProperty>;
  requiredFields?: string[];
  items?: AuthoringSchemaProperty;
  minItems?: number;
  maxItems?: number;
  itemIdentityField?: string;
  duplicationPolicy?: "allow" | "deny";
  enumValues?: Array<string | AuthoringEnumOption>;
  ui?: UiPresentationMetadata;
  reference?: AuthoringReferenceSemantics;
};

export type AuthoringInvariant = {
  name: string;
  type?: string;
  fields?: string[];
  expr?: string;
  expression?: string;
};

export type AuthoringLifecycleState = {
  value: string;
  label?: string;
  initial?: boolean;
  terminal?: boolean;
  metadata?: Record<string, string>;
};

export type AuthoringLifecycleTransition = {
  from: string;
  to: string;
  requiredPayload?: string[];
  guard?: string;
  event?: string;
  actionLabel?: string;
  action?: UiActionMetadata;
  metadata?: Record<string, string>;
};

export type AuthoringLifecycle = {
  statusField?: string;
  states?: AuthoringLifecycleState[];
  transitions?: AuthoringLifecycleTransition[];
};

export type AuthoringEntity = {
  name: string;
  ui?: UiPresentationMetadata;
  fields: AuthoringField[];
  invariants?: AuthoringInvariant[];
  lifecycle?: AuthoringLifecycle;
};

export type AuthoringCapabilityOperation =
  | string
  | {
      name: string;
      input?: string[];
    };

export type AuthoringCapability = {
  name: string;
  type: string;
  operations?: AuthoringCapabilityOperation[];
};

export type AuthoringBinding = {
  capability: string;
  adapter: string;
};

export type AuthoringEventPayloadField = {
  name: string;
  type: string;
};

export type AuthoringEventDefinition = {
  name: string;
  payload?: AuthoringEventPayloadField[];
};

export type AuthoringOrchestrationAction = {
  type: string;
  concept?: string;
  capability?: string;
  operation?: string;
  action?: UiActionMetadata;
  map?: Record<string, string>;
};

export type AuthoringOrchestrationRule = {
  name: string;
  trigger?: {
    type?: string;
    event?: string;
  };
  condition?: string;
  actions?: AuthoringOrchestrationAction[];
};

export type AuthoringFlowStep = {
  name: string;
  type: string;
  scope?: string;
  input?: string;
  out?: string;
  value?: string;
  condition?: string;
  event?: string;
  delayMinutes?: number;
  invariants?: string[];
  data?: Record<string, string>;
  then?: AuthoringFlowStep[];
  action?: UiActionMetadata;
};

export type AuthoringFlow = {
  name: string;
  input?: {
    concept?: string;
    mode?: string;
  };
  action?: UiActionMetadata;
  steps?: AuthoringFlowStep[];
};

export type AuthoringQuerySort = {
  field: string;
  direction?: string;
};

export type AuthoringProcedureParameter = {
  name: string;
  type: string;
  required?: boolean;
  schema?: AuthoringSchemaProperty;
  description?: string;
};

export type AuthoringProcedureVariable = {
  name: string;
  type?: string;
  schema?: AuthoringSchemaProperty;
  initialValue?: AuthoringModelPrimitive;
};

export type AuthoringQuery = {
  name: string;
  concept: string;
  where?: string;
  filter?: string;
  orderBy?: string[];
  sort?: AuthoringQuerySort[];
  limit?: number;
  parameters?: AuthoringProcedureParameter[];
  permissionRequirements?: string[];
  permissions?: string[];
  tracePolicy?: string;
  auditPolicy?: string;
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringRuleProfile = {
  name: string;
  description?: string;
  concept?: string;
  appliesTo?: string[];
  invariants?: string[];
  enabled?: boolean;
  permissionRequirements?: string[];
  permissions?: string[];
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringProcedureStep = {
  name?: string;
  type: string;
  target?: string;
  value?: AuthoringModelPrimitive;
  condition?: string;
  items?: string;
  as?: string;
  concept?: string;
  query?: string;
  data?: Record<string, AuthoringModelPrimitive>;
  input?: string;
  out?: string;
  set?: Record<string, AuthoringModelPrimitive>;
  id?: string;
  procedure?: string;
  capability?: string;
  operation?: string;
  event?: string;
  args?: Record<string, AuthoringModelPrimitive>;
  then?: AuthoringProcedureStep[];
  else?: AuthoringProcedureStep[];
  steps?: AuthoringProcedureStep[];
  trace?: boolean;
  audit?: boolean;
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringProcedure = {
  name: string;
  description?: string;
  parameters?: AuthoringProcedureParameter[];
  locals?: AuthoringProcedureVariable[];
  variables?: AuthoringProcedureVariable[];
  steps: AuthoringProcedureStep[];
  returns?: AuthoringSchemaProperty;
  permissionRequirements?: string[];
  tracePolicy?: string;
  auditPolicy?: string;
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringPanelDataSource = {
  type?: string;
  name: string;
  concept?: string;
  query?: string;
  procedure?: string;
  params?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringPanelLayout = {
  type: string;
  columns?: number;
  children?: AuthoringPanelLayout[];
  fields?: string[];
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringPanelField = {
  field: string;
  source?: string;
  visible?: boolean;
  editable?: boolean;
  visibleWhen?: string;
  enabledWhen?: string;
  readonlyWhen?: string;
  ui?: UiPresentationMetadata;
};

export type AuthoringPanelAction = {
  name: string;
  binding?: string;
  label?: string;
  concept?: string;
  operation?: string;
  procedure?: string;
  flow?: string;
  visibleWhen?: string;
  enabledWhen?: string;
  permissionRequirements?: string[];
  explainability?: Record<string, AuthoringModelPrimitive>;
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringPanel = {
  name: string;
  route?: string;
  title?: string;
  concept?: string;
  dataSource?: AuthoringPanelDataSource;
  dataSources?: AuthoringPanelDataSource[];
  layout?: AuthoringPanelLayout;
  fields?: AuthoringPanelField[];
  fieldBindings?: AuthoringPanelField[];
  visibility?: string;
  enabledWhen?: string;
  actions?: AuthoringPanelAction[];
  explainability?: Record<string, AuthoringModelPrimitive>;
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringModelDocument = {
  $schema?: string;
  namespace: string;
  dslVersion?: string;
  version: string;
  domainTypes: AuthoringDomainType[];
  concepts: AuthoringEntity[];
  capabilities: AuthoringCapability[];
  bindings: AuthoringBinding[];
  events: AuthoringEventDefinition[];
  orchestrationRules: AuthoringOrchestrationRule[];
  flows: AuthoringFlow[];
  queries: AuthoringQuery[];
  ruleProfiles: AuthoringRuleProfile[];
  procedures: AuthoringProcedure[];
  panels: AuthoringPanel[];
  metadata?: Record<string, AuthoringModelPrimitive>;
};

export type AuthoringDocumentSession = {
  sourceKey: string;
  sourceLabel: string;
  document: AuthoringModelDocument;
  dirty: boolean;
  lastLoadedLabel: string;
};
