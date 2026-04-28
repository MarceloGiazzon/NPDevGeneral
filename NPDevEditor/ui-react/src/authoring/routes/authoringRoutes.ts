export type AuthoringRouteId =
  | "home"
  | "model-selector"
  | "model-editor"
  | "config-editor"
  | "preview"
  | "validation"
  | "import-export";

export type AuthoringRouteDef = {
  id: AuthoringRouteId;
  segment: string;
  label: string;
  summary: string;
};

export type AuthoringDeepLinkParams = {
  concept?: string | null;
  field?: string | null;
  section?: string | null;
};

export type ParsedAuthoringLocation = {
  route: AuthoringRouteDef;
  params: AuthoringDeepLinkParams;
};

export const AUTHORING_ROUTES: AuthoringRouteDef[] = [
  {
    id: "home",
    segment: "home",
    label: "Home",
    summary: "Launch point for the authoring workspace and model-entry modes."
  },
  {
    id: "model-selector",
    segment: "models",
    label: "Model Selector",
    summary: "Reserved shell for category-aware model selection and startup flows."
  },
  {
    id: "model-editor",
    segment: "editor",
    label: "Model Editor",
    summary: "Guided concept, field, rule, flow, and metadata authoring surface."
  },
  {
    id: "config-editor",
    segment: "config",
    label: "Config Editor",
    summary: "Guided runtime, projection, and environment configuration authoring surface."
  },
  {
    id: "preview",
    segment: "preview",
    label: "Preview",
    summary: "Metadata-driven preview workspace for forms, tables, pickers, actions, and layout."
  },
  {
    id: "validation",
    segment: "validation",
    label: "Validation",
    summary: "Explainable diagnostics workspace for model, config, and interaction validation."
  },
  {
    id: "import-export",
    segment: "exchange",
    label: "Import / Export",
    summary: "Operational workspace for bundle import, export, versioned save, and semantic diff."
  }
];

export const AUTHORING_DEFAULT_ROUTE_ID: AuthoringRouteId = "home";

export function findAuthoringRoute(routeId: AuthoringRouteId): AuthoringRouteDef {
  return (
    AUTHORING_ROUTES.find((route) => route.id === routeId) ??
    AUTHORING_ROUTES.find((route) => route.id === AUTHORING_DEFAULT_ROUTE_ID)!
  );
}

export function routeForSegment(segment: string | null | undefined): AuthoringRouteDef {
  const normalized = (segment ?? "").trim().toLowerCase();
  return (
    AUTHORING_ROUTES.find((route) => route.segment === normalized) ??
    AUTHORING_ROUTES.find((route) => route.id === AUTHORING_DEFAULT_ROUTE_ID)!
  );
}

export function authoringHashFor(routeId: AuthoringRouteId, params?: AuthoringDeepLinkParams): string {
  const query = new URLSearchParams();
  if (params?.concept) {
    query.set("concept", params.concept);
  }
  if (params?.field) {
    query.set("field", params.field);
  }
  if (params?.section) {
    query.set("section", params.section);
  }
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return `#/authoring/${findAuthoringRoute(routeId).segment}${suffix}`;
}

export function parseAuthoringHash(hashValue: string): ParsedAuthoringLocation {
  const normalized = hashValue.replace(/^#/, "");
  const [pathPart, queryPart = ""] = normalized.split("?");
  const parts = pathPart.split("/").filter(Boolean);
  const segment = parts[1] ?? "";
  const query = new URLSearchParams(queryPart);

  return {
    route: routeForSegment(segment),
    params: {
      concept: query.get("concept"),
      field: query.get("field"),
      section: query.get("section")
    }
  };
}
