import {
  DEFAULT_STARTER_TEMPLATE_ID,
  getStarterTemplateCard,
  listStarterTemplateCards as listStarterTemplateCardsInternal,
  type StarterTemplateCard,
  type StarterTemplateId
} from "../templates/starterTemplates";

export type AuthoringStartModeId =
  | "canonical-demo"
  | "official-samples"
  | "arbitrary-model"
  | "import-existing"
  | "new-model";

export type AuthoringStartMode = {
  id: AuthoringStartModeId;
  title: string;
  description: string;
  recommended: boolean;
};

export type AuthoringWorkspaceSeed = {
  title: string;
  description: string;
  modelSource: AuthoringStartModeId;
  entryCategory: string;
  primaryActionLabel: string;
  recommendedAudience: string;
  sampleId?: string;
  templateId?: StarterTemplateId;
  templateTitle?: string;
};

export type AuthoringCategoryCard = {
  id: AuthoringStartModeId;
  title: string;
  description: string;
  bestFor: string;
  caution: string;
  beginnerSafe: boolean;
  routeHint: string;
};

export type OfficialSampleCard = {
  id: string;
  title: string;
  complexity: string;
  scenarioFocus: string;
  description: string;
  learningGoals: string[];
  features: string[];
  expectedOutcomes: string[];
};

const START_MODES: AuthoringStartMode[] = [
  {
    id: "canonical-demo",
    title: "Canonical Demo",
    description: "Learn from the frozen canonical demo and the canonical baseline specimen.",
    recommended: true
  },
  {
    id: "official-samples",
    title: "Official Samples",
    description: "Compare curated reference models without leaving the authoring shell.",
    recommended: false
  },
  {
    id: "arbitrary-model",
    title: "Arbitrary Model",
    description: "Attach an existing model path when you are ready to work from your own source.",
    recommended: false
  },
  {
    id: "import-existing",
    title: "Import Existing",
    description: "Bring in files for round-trip authoring, validation, and export workflows.",
    recommended: false
  },
  {
    id: "new-model",
    title: "New Model",
    description: "Start a blank authoring session with guided structure instead of raw JSON only.",
    recommended: false
  }
];

export function listAuthoringStartModes(): AuthoringStartMode[] {
  return START_MODES;
}

export function buildWorkspaceSeed(modeId: AuthoringStartModeId = "canonical-demo"): AuthoringWorkspaceSeed {
  const mode = START_MODES.find((entry) => entry.id === modeId) ?? START_MODES[0];
  const starterTemplate = mode.id === "new-model" ? getStarterTemplateCard(DEFAULT_STARTER_TEMPLATE_ID) : null;

  return {
    title: starterTemplate?.title ?? mode.title,
    description: starterTemplate?.description ?? mode.description,
    modelSource: mode.id,
    entryCategory: mode.id,
    primaryActionLabel: starterTemplate ? `Open ${starterTemplate.title}` : defaultActionLabel(mode.id),
    recommendedAudience: starterTemplate ? starterTemplate.goodFor : recommendedAudience(mode.id),
    templateId: starterTemplate?.id,
    templateTitle: starterTemplate?.title
  };
}

const CATEGORY_CARDS: AuthoringCategoryCard[] = [
  {
    id: "canonical-demo",
    title: "Canonical Demo",
    description: "The frozen canonical specimen used by docs and regression flows.",
    bestFor: "The safest starting point for beginners and for canonical exploration.",
    caution: "Intentionally stable. Changes here are platform-significant, not casual experimentation.",
    beginnerSafe: true,
    routeHint: "Start here if you want the most guided NPDev path."
  },
  {
    id: "official-samples",
    title: "Official Samples",
    description: "Curated onboarding scenarios that show progressively richer NPDev behaviors.",
    bestFor: "Learning through smaller or alternate scenarios before touching your own model.",
    caution: "Curated samples are editable later, but each one teaches a specific scenario.",
    beginnerSafe: true,
    routeHint: "Choose a sample when you want a focused learning slice instead of the full canonical demo."
  },
  {
    id: "arbitrary-model",
    title: "Arbitrary Model",
    description: "Open-ended entry for an existing model path or workspace-local source.",
    bestFor: "Users who already have a model and want to author against their own source.",
    caution: "Least guided path. Bring this in when you already understand the shell structure.",
    beginnerSafe: false,
    routeHint: "Best after you understand canonical demo or official sample patterns."
  },
  {
    id: "import-existing",
    title: "Import Existing",
    description: "Round-trip an existing file set through the authoring workspace.",
    bestFor: "Resuming existing work while preserving a future import/export workflow boundary.",
    caution: "Import UX is still a shell. Step 32 just gives it a clear front door.",
    beginnerSafe: false,
    routeHint: "Use when you know you are continuing from real files rather than learning from a platform specimen."
  },
  {
    id: "new-model",
    title: "New Model",
    description: "Start a fresh model authoring session from a blank guided workspace.",
    bestFor: "Creating a new domain after you know the main concepts you want to model.",
    caution: "More freedom means fewer guardrails than the canonical or sample lanes.",
    beginnerSafe: false,
    routeHint: "Best for users who are ready to design their own model shape."
  }
];

const OFFICIAL_SAMPLES: OfficialSampleCard[] = [
  {
    id: "simple-user-registry",
    title: "Simple User Registry",
    complexity: "simple",
    scenarioFocus: "single-record create flow",
    description: "Smallest useful NPDev sample. Shows concept, invariants, persistence, event emission, and trace visibility.",
    learningGoals: [
      "Understand a concept-centered model",
      "Observe invariant enforcement in a simple form submission",
      "See persistence and event emission in one flow"
    ],
    features: ["concept", "invariants", "persistence", "event", "trace"],
    expectedOutcomes: [
      "A user record is persisted",
      "A UserCreated-style event is emitted",
      "Execution trace and timeline are available for inspection"
    ]
  },
  {
    id: "simple-contact-intake",
    title: "Simple Contact Intake",
    complexity: "simple",
    scenarioFocus: "inbound request capture",
    description: "Adds notification capability to the basic pattern and shows governed side effects in a beginner-friendly scenario.",
    learningGoals: [
      "See capability invocation in a beginner-friendly scenario",
      "Observe event and notification behavior together",
      "Compare a slightly richer sample with the simplest baseline"
    ],
    features: ["concept", "invariants", "persistence", "notification", "event", "trace"],
    expectedOutcomes: [
      "A contact message is persisted",
      "A notification capability is invoked",
      "An event is emitted and trace evidence is available"
    ]
  },
  {
    id: "medium-expense-approval",
    title: "Medium Expense Approval",
    complexity: "medium",
    scenarioFocus: "approval and resume workflow",
    description: "The first medium sample, showing await-event, branching, persistence, notification, webhook behavior, and resumable orchestration.",
    learningGoals: [
      "Understand waiting and resuming business processes",
      "See branching based on later external input",
      "Observe a richer orchestration than the simple samples"
    ],
    features: ["concept", "invariants", "persistence", "notification", "webhook", "awaitEvent", "branch", "trace"],
    expectedOutcomes: [
      "Expense submission persists the business record",
      "The flow waits for a later approval-style event",
      "After the event, the flow resumes down the correct branch"
    ]
  }
];

function defaultActionLabel(modeId: AuthoringStartModeId): string {
  switch (modeId) {
    case "canonical-demo":
      return "Open canonical workspace";
    case "official-samples":
      return "Choose an official sample";
    case "arbitrary-model":
      return "Attach existing model";
    case "import-existing":
      return "Prepare import session";
    case "new-model":
      return "Create new authoring draft";
    default:
      return "Open authoring workspace";
  }
}

function recommendedAudience(modeId: AuthoringStartModeId): string {
  switch (modeId) {
    case "canonical-demo":
      return "Beginners and canonical exploration";
    case "official-samples":
      return "Guided learning through curated scenarios";
    case "arbitrary-model":
      return "Experienced users with an existing model path";
    case "import-existing":
      return "Existing file-based work that needs a guided workspace";
    case "new-model":
      return "Users starting a fresh domain after learning the basics";
    default:
      return "General authoring";
  }
}

export function listAuthoringCategoryCards(): AuthoringCategoryCard[] {
  return CATEGORY_CARDS;
}

export function listOfficialSampleCards(): OfficialSampleCard[] {
  return OFFICIAL_SAMPLES;
}

export function listStarterTemplateCards(): StarterTemplateCard[] {
  return listStarterTemplateCardsInternal();
}

export function buildOfficialSampleWorkspaceSeed(sampleId: string): AuthoringWorkspaceSeed {
  const sample = OFFICIAL_SAMPLES.find((entry) => entry.id === sampleId) ?? OFFICIAL_SAMPLES[0];
  return {
    title: sample.title,
    description: sample.description,
    modelSource: "official-samples",
    entryCategory: "official-samples",
    primaryActionLabel: "Open sample in editor shell",
    recommendedAudience: `${sample.complexity} sample focused on ${sample.scenarioFocus}`,
    sampleId: sample.id
  };
}

export function buildStarterTemplateWorkspaceSeed(templateId: StarterTemplateId): AuthoringWorkspaceSeed {
  const template = getStarterTemplateCard(templateId);
  return {
    title: template.title,
    description: template.description,
    modelSource: "new-model",
    entryCategory: "new-model",
    primaryActionLabel: `Open ${template.title}`,
    recommendedAudience: template.goodFor,
    templateId: template.id,
    templateTitle: template.title
  };
}

