import type { ValidationDiagnostic } from "../../types";
import {
  authoringHashFor,
  type AuthoringDeepLinkParams,
  type AuthoringRouteId
} from "../routes/authoringRoutes";

export type StartHerePath = {
  id: "canonical-demo" | "official-samples" | "new-model";
  title: string;
  description: string;
  routeHint: string;
  actionLabel: string;
  recommended?: boolean;
};

export type PipelineStep = {
  id: "edit" | "validate" | "preview" | "export" | "build" | "run";
  label: string;
  detail: string;
  routeId?: AuthoringRouteId;
  active: boolean;
};

export type DiagnosticNavigationTarget = {
  routeId: AuthoringRouteId;
  hash: string;
  locationLabel: string;
  focusSection: string | null;
};

export const START_HERE_PATHS: StartHerePath[] = [
  {
    id: "canonical-demo",
    title: "Canonical demo",
    description: "The fully documented reference system. Best for learning NPDev safely.",
    routeHint: "Open directly into the guided model editor",
    actionLabel: "Open canonical demo",
    recommended: true
  },
  {
    id: "official-samples",
    title: "Official samples",
    description: "Smaller curated examples when you want a narrower starting point than the full demo.",
    routeHint: "Open the chooser and pick a sample",
    actionLabel: "Browse official samples"
  },
  {
    id: "new-model",
    title: "Start from scratch",
    description: "Create a fresh guided draft once you understand the basics of the authoring flow.",
    routeHint: "Open the editor with a new starter draft",
    actionLabel: "Start a new model"
  }
];

function isEditRoute(routeId: AuthoringRouteId): boolean {
  return routeId === "home" || routeId === "model-selector" || routeId === "model-editor" || routeId === "config-editor";
}

export function buildPipelineSteps(activeRouteId: AuthoringRouteId): PipelineStep[] {
  return [
    {
      id: "edit",
      label: "Edit",
      detail: "Pick a starting path and shape the model or config.",
      routeId: "model-editor",
      active: isEditRoute(activeRouteId)
    },
    {
      id: "validate",
      label: "Validate",
      detail: "Review semantic and structural diagnostics before handoff.",
      routeId: "validation",
      active: activeRouteId === "validation"
    },
    {
      id: "preview",
      label: "Preview",
      detail: "Inspect projected UI and behavior before export.",
      routeId: "preview",
      active: activeRouteId === "preview"
    },
    {
      id: "export",
      label: "Export",
      detail: "Download canonical artifacts and package the supported handoff.",
      routeId: "import-export",
      active: activeRouteId === "import-export"
    },
    {
      id: "build",
      label: "Build",
      detail: "Generator and runtime build come after export.",
      active: false
    },
    {
      id: "run",
      label: "Run",
      detail: "Quickstart and runtime verification happen after build.",
      active: false
    }
  ];
}

function focusSectionForDiagnostic(diagnostic: ValidationDiagnostic): string | null {
  if (diagnostic.sourceModule.includes("config")) {
    return "config-editor";
  }
  if (diagnostic.section === "flows") {
    return "flows";
  }
  if (diagnostic.field && diagnostic.code.includes("reference")) {
    return "references";
  }
  if (diagnostic.field) {
    return "fields";
  }
  if (diagnostic.concept) {
    return "concepts";
  }
  return null;
}

function routeForDiagnostic(diagnostic: ValidationDiagnostic): AuthoringRouteId {
  return diagnostic.sourceModule.includes("config") ? "config-editor" : "model-editor";
}

function buildLocationLabel(diagnostic: ValidationDiagnostic): string {
  if (diagnostic.concept && diagnostic.field) {
    return `${diagnostic.concept}.${diagnostic.field}`;
  }
  if (diagnostic.concept) {
    return diagnostic.concept;
  }
  return diagnostic.path ?? diagnostic.code;
}

export function buildDiagnosticNavigationTarget(diagnostic: ValidationDiagnostic): DiagnosticNavigationTarget {
  const routeId = routeForDiagnostic(diagnostic);
  const focusSection = focusSectionForDiagnostic(diagnostic);
  const params: AuthoringDeepLinkParams =
    routeId === "model-editor"
      ? {
          concept: diagnostic.concept ?? null,
          field: diagnostic.field ?? null,
          section: focusSection
        }
      : {
          section: focusSection
        };

  return {
    routeId,
    hash: authoringHashFor(routeId, params),
    locationLabel: buildLocationLabel(diagnostic),
    focusSection
  };
}
