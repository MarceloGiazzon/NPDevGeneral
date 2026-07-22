import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import type {
  AuthoringEntity,
  AuthoringFlow,
  AuthoringLifecycle,
  AuthoringModelDocument
} from "../editors/modelDocumentTypes";

export type StarterTemplateId = "blank-business-record" | "case-intake" | "approval-workflow";

export type StarterTemplateCard = {
  id: StarterTemplateId;
  title: string;
  description: string;
  useCase: string;
  goodFor: string;
  complexity: "simple" | "medium";
  starterNotes: string[];
};

export const DEFAULT_STARTER_TEMPLATE_ID: StarterTemplateId = "blank-business-record";

const STARTER_TEMPLATES: StarterTemplateCard[] = [
  {
    id: "blank-business-record",
    title: "Business Record Starter",
    description: "A smallest-useful draft with one concept, one flow, and stable persistence wiring.",
    useCase: "Good when you want to learn the editor without committing to a complex scenario yet.",
    goodFor: "Beginners creating their first custom NPDev model.",
    complexity: "simple",
    starterNotes: [
      "Starts with one concept and one create flow.",
      "Keeps the shape small so you can learn concepts, fields, and flows incrementally."
    ]
  },
  {
    id: "case-intake",
    title: "Case Intake Starter",
    description: "A guided intake-style draft focused on submission, triage status, and follow-up fields.",
    useCase: "Good for help desk, support, intake, request, or form-submission style domains.",
    goodFor: "Teams that want a realistic starting point without jumping into the full canonical demo.",
    complexity: "simple",
    starterNotes: [
      "Prepares one intake concept with practical starter fields.",
      "Adds status metadata so lifecycle and preview screens become meaningful quickly."
    ]
  },
  {
    id: "approval-workflow",
    title: "Approval Workflow Starter",
    description: "A guided draft for submit-review-approve/reject scenarios with lifecycle metadata already sketched.",
    useCase: "Good for approvals, reviews, requests, or governed multi-step business processes.",
    goodFor: "Users who already understand the basics and want a more process-oriented starter.",
    complexity: "medium",
    starterNotes: [
      "Includes lifecycle states and a more intentional flow skeleton.",
      "Helps demonstrate transitions, action labels, and richer flow thinking early."
    ]
  }
];

function cloneDocument<T>(document: T): T {
  return JSON.parse(JSON.stringify(document)) as T;
}

function buildPersistenceCapability() {
  return {
    name: "persistence",
    type: "PersistenceCapability",
    operations: ["save", "findById"]
  };
}

function buildPersistenceBinding() {
  return {
    capability: "persistence",
    adapter: "repository"
  };
}

function buildRecordEntity(): AuthoringEntity {
  return {
    name: "BusinessRecord",
    ui: {
      label: "Business record",
      description: "Starter concept for a straightforward create-and-track scenario."
    },
    fields: [
      {
        name: "id",
        type: "uuid",
        id: true,
        required: true
      },
      {
        name: "name",
        type: "string",
        required: true,
        ui: {
          label: "Record name",
          order: 1
        }
      },
      {
        name: "status",
        type: "string",
        required: true,
        default: "Draft",
        enumValues: [
          { value: "Draft", label: "Draft", default: true, order: 1, badge: "neutral" },
          { value: "Active", label: "Active", order: 2, badge: "success" }
        ],
        ui: {
          label: "Status",
          order: 2
        }
      }
    ],
    invariants: []
  };
}

function buildCaseEntity(): AuthoringEntity {
  return {
    name: "IntakeCase",
    ui: {
      label: "Intake case",
      description: "Starter concept for customer intake, requests, or support-style scenarios."
    },
    fields: [
      {
        name: "id",
        type: "uuid",
        id: true,
        required: true
      },
      {
        name: "requesterName",
        type: "string",
        required: true,
        ui: {
          label: "Requester name",
          order: 1
        }
      },
      {
        name: "requesterEmail",
        type: "string",
        ui: {
          label: "Requester email",
          order: 2
        }
      },
      {
        name: "subject",
        type: "string",
        required: true,
        ui: {
          label: "Subject",
          order: 3
        }
      },
      {
        name: "priority",
        type: "string",
        default: "Normal",
        enumValues: [
          { value: "Low", label: "Low", order: 1, badge: "neutral" },
          { value: "Normal", label: "Normal", default: true, order: 2, badge: "info" },
          { value: "High", label: "High", order: 3, badge: "warning" }
        ],
        ui: {
          label: "Priority",
          order: 4
        }
      },
      {
        name: "status",
        type: "string",
        default: "New",
        enumValues: [
          { value: "New", label: "New", default: true, order: 1, badge: "info" },
          { value: "Triaged", label: "Triaged", order: 2, badge: "warning" },
          { value: "Closed", label: "Closed", order: 3, badge: "success" }
        ],
        ui: {
          label: "Status",
          order: 5
        }
      }
    ],
    invariants: []
  };
}

function buildApprovalLifecycle(): AuthoringLifecycle {
  return {
    statusField: "status",
    states: [
      { value: "Draft", label: "Draft", initial: true },
      { value: "Submitted", label: "Submitted" },
      { value: "Approved", label: "Approved", terminal: true },
      { value: "Rejected", label: "Rejected", terminal: true }
    ],
    transitions: [
      {
        from: "Draft",
        to: "Submitted",
        actionLabel: "Submit request"
      },
      {
        from: "Submitted",
        to: "Approved",
        actionLabel: "Approve request"
      },
      {
        from: "Submitted",
        to: "Rejected",
        actionLabel: "Reject request"
      }
    ]
  };
}

function buildApprovalEntity(): AuthoringEntity {
  return {
    name: "ApprovalRequest",
    ui: {
      label: "Approval request",
      description: "Starter concept for governed review and approval processes."
    },
    fields: [
      {
        name: "id",
        type: "uuid",
        id: true,
        required: true
      },
      {
        name: "title",
        type: "string",
        required: true,
        ui: {
          label: "Request title",
          order: 1
        }
      },
      {
        name: "requester",
        type: "string",
        ui: {
          label: "Requester",
          order: 2
        }
      },
      {
        name: "amount",
        type: "number",
        ui: {
          label: "Amount",
          order: 3
        }
      },
      {
        name: "status",
        type: "string",
        required: true,
        default: "Draft",
        enumValues: [
          { value: "Draft", label: "Draft", default: true, order: 1, badge: "neutral" },
          { value: "Submitted", label: "Submitted", order: 2, badge: "info" },
          { value: "Approved", label: "Approved", order: 3, badge: "success" },
          { value: "Rejected", label: "Rejected", order: 4, badge: "danger" }
        ],
        ui: {
          label: "Status",
          order: 4
        }
      }
    ],
    invariants: [],
    lifecycle: buildApprovalLifecycle()
  };
}

function buildFlow(name: string, concept: string): AuthoringFlow {
  return {
    name,
    input: {
      concept,
      mode: "create"
    },
    action: {
      label: name.replace(/([a-z])([A-Z])/g, "$1 $2")
    },
    steps: [
      {
        name: "validate-input",
        type: "validate",
        value: "$input"
      },
      {
        name: "return-input",
        type: "return",
        value: "$input"
      }
    ]
  };
}

function buildTemplateModel(templateId: StarterTemplateId): AuthoringModelDocument {
  const modelSchemaId = "https://npdev.local/schema/npdev-model.schema.json";
  switch (templateId) {
    case "case-intake":
      return {
        $schema: modelSchemaId,
        namespace: "authoring.caseintake",
        dslVersion: "1.0.0",
        version: "1.0",
        domainTypes: [],
        concepts: [buildCaseEntity()],
        capabilities: [buildPersistenceCapability()],
        bindings: [buildPersistenceBinding()],
        events: [],
        orchestrationRules: [],
        flows: [buildFlow("SubmitCase", "IntakeCase")],
        queries: [],
        ruleProfiles: [],
        procedures: [],
        panels: []
      };
    case "approval-workflow":
      return {
        $schema: modelSchemaId,
        namespace: "authoring.approvalworkflow",
        dslVersion: "1.0.0",
        version: "1.0",
        domainTypes: [],
        concepts: [buildApprovalEntity()],
        capabilities: [buildPersistenceCapability()],
        bindings: [buildPersistenceBinding()],
        events: [],
        orchestrationRules: [],
        flows: [buildFlow("SubmitApprovalRequest", "ApprovalRequest")],
        queries: [],
        ruleProfiles: [],
        procedures: [],
        panels: []
      };
    case "blank-business-record":
    default:
      return {
        $schema: modelSchemaId,
        namespace: "authoring.businessrecord",
        dslVersion: "1.0.0",
        version: "1.0",
        domainTypes: [],
        concepts: [buildRecordEntity()],
        capabilities: [buildPersistenceCapability()],
        bindings: [buildPersistenceBinding()],
        events: [],
        orchestrationRules: [],
        flows: [buildFlow("CreateBusinessRecord", "BusinessRecord")],
        queries: [],
        ruleProfiles: [],
        procedures: [],
        panels: []
      };
  }
}

export function listStarterTemplateCards(): StarterTemplateCard[] {
  return STARTER_TEMPLATES;
}

export function getStarterTemplateCard(templateId: StarterTemplateId): StarterTemplateCard {
  return STARTER_TEMPLATES.find((entry) => entry.id === templateId) ?? STARTER_TEMPLATES[0];
}

export function buildStarterTemplateModel(templateId: StarterTemplateId): AuthoringModelDocument {
  return cloneDocument(buildTemplateModel(templateId));
}

export function describeStarterTemplateForConfig(templateId: StarterTemplateId): string {
  const template = getStarterTemplateCard(templateId);
  return `Guided config seed created from starter template ${template.title}.`;
}

export function applyStarterTemplateConfigMetadata(
  document: AuthoringConfigDocument,
  templateId: StarterTemplateId
): AuthoringConfigDocument {
  const template = getStarterTemplateCard(templateId);
  return {
    ...document,
    metadata: {
      ...document.metadata,
      projectionPreset: "starter-template",
      notes: `Guided config seed created from starter template ${template.title}.`,
      samplePresetLabel: template.id
    }
  };
}
