import type { ValidationDiagnostic } from "../../types";
import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import type { AuthoringModelDocument } from "../editors/modelDocumentTypes";

export type DiagnosticLinkItem = {
  title: string;
  source: string;
  expectation: string;
  evidenceHint: string;
};

export function buildDiagnosticLinkItems(
  document: AuthoringModelDocument,
  config: AuthoringConfigDocument | null,
  diagnostics: ValidationDiagnostic[]
): DiagnosticLinkItem[] {
  const items: DiagnosticLinkItem[] = [];

  for (const diagnostic of diagnostics.slice(0, 8)) {
    items.push({
      title: diagnostic.message,
      source: diagnostic.path ?? diagnostic.code,
      expectation:
        diagnostic.layer === "ux-metadata"
          ? "This should surface as an authoring-time metadata problem before generation."
          : diagnostic.layer === "semantic"
            ? "This should be reflected in semantic validation and may affect generated runtime behavior."
            : "This should fail or warn during structural validation before handoff.",
      evidenceHint:
        diagnostic.section === "flows"
          ? "Expect flow-oriented validation output and eventual trace mismatches if left unresolved."
          : diagnostic.sourceModule.includes("config")
            ? "Expect export or runtime configuration issues if this remains unresolved."
            : "Expect authoring validation warnings and possibly confusing preview/runtime interpretation."
    });
  }

  for (const flow of document.flows.slice(0, 4)) {
    items.push({
      title: `Trace expectation for ${flow.name}`,
      source: flow.name,
      expectation: "A generated or runtime execution trace should reflect this flow and its declared steps.",
      evidenceHint: `Look for execution/tracing evidence around ${flow.steps?.map((step) => step.name).join(", ") || "its declared steps"}.`
    });
  }

  if (config?.metadata?.capabilityBindings?.length) {
    for (const binding of config.metadata.capabilityBindings.slice(0, 4)) {
      items.push({
        title: `Capability binding: ${binding.capability}`,
        source: binding.target || binding.mode,
        expectation: "Runtime integration and metadata-driven explanations should align with this capability binding.",
        evidenceHint: "Check projection metadata, runtime UI metadata, and capability-specific behavior for consistency."
      });
    }
  }

  return items;
}
