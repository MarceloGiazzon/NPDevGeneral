import type { AuthoringConfigDocument } from "../config/configDocumentTypes";
import type { AuthoringModelDocument } from "../editors/modelDocumentTypes";

export type SemanticGraphNode = {
  id: string;
  label: string;
  kind: "concept" | "flow" | "capability" | "event";
  summary: string;
};

export type SemanticGraphEdge = {
  from: string;
  to: string;
  label: string;
  warning?: boolean;
};

/** One column in an ER-diagram table box -- one row per concept field. */
export type ErColumn = {
  name: string;
  type: string;
  isPrimaryKey: boolean;
  isForeignKey: boolean;
  required: boolean;
};

/** One concept rendered as an ER-diagram table box. */
export type ErTable = {
  id: string;
  name: string;
  columns: ErColumn[];
};

/** One reference field rendered as a connector line between two ER-diagram tables.
 *  `fromTable`/`fromColumn` is always the "many" (FK-owning) side; `toTable` is the referenced
 *  ("one", unless `manyToMany`) side, anchored on its `via` column (defaults to its primary key). */
export type ErRelationship = {
  id: string;
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
  manyToMany: boolean;
};

export type SemanticGraphModel = {
  nodes: SemanticGraphNode[];
  edges: SemanticGraphEdge[];
  erTables: ErTable[];
  erRelationships: ErRelationship[];
};

export function buildSemanticGraph(
  document: AuthoringModelDocument,
  config?: AuthoringConfigDocument | null
): SemanticGraphModel {
  const nodes: SemanticGraphNode[] = [];
  const edges: SemanticGraphEdge[] = [];
  const erTables: ErTable[] = [];
  const erRelationships: ErRelationship[] = [];

  for (const entity of document.concepts ?? []) {
    nodes.push({
      id: `concept:${entity.name}`,
      label: entity.name,
      kind: "concept",
      summary: `${entity.truthLevel ?? "T1"} · ${entity.fields.length} fields, ${(entity.invariants ?? []).length} invariants`
    });

    erTables.push({
      id: entity.name,
      name: entity.name,
      columns: entity.fields.map((field) => ({
        name: field.name,
        type: field.type ?? "string",
        isPrimaryKey: field.id === true,
        isForeignKey: field.type === "reference" && Boolean(field.reference?.target),
        required: field.required === true || field.id === true
      }))
    });

    for (const field of entity.fields.filter((entry) => entry.type === "reference" && entry.reference?.target)) {
      const target = document.concepts.find((candidate) => candidate.name === field.reference?.target);
      const upwardTruthEdge = truthRank(entity.truthLevel) > truthRank(target?.truthLevel);
      edges.push({
        from: `concept:${entity.name}`,
        to: `concept:${field.reference?.target}`,
        label: [
          `reference:${field.name}`,
          field.reference?.via ? `via:${field.reference.via}` : "via:id",
          field.reference?.onDelete ? `delete:${field.reference.onDelete}` : "delete:restrict",
          field.reference?.multiple ? "multiple" : "",
          upwardTruthEdge ? "truth-warning" : ""
        ].filter(Boolean).join(" · "),
        warning: upwardTruthEdge
      });

      if (target) {
        const anchorName = field.reference?.via ?? "id";
        const anchorColumn = target.fields.find((entry) => entry.name === anchorName);
        erRelationships.push({
          id: `${entity.name}.${field.name}->${field.reference?.target}`,
          fromTable: entity.name,
          fromColumn: field.name,
          toTable: field.reference?.target ?? "",
          toColumn: anchorColumn ? anchorColumn.name : anchorName,
          manyToMany: field.reference?.multiple === true
        });
      }
    }
  }

  for (const flow of document.flows ?? []) {
    nodes.push({
      id: `flow:${flow.name}`,
      label: flow.name,
      kind: "flow",
      summary: `${flow.steps?.length ?? 0} steps`
    });
    if (flow.input?.concept) {
      edges.push({
        from: `flow:${flow.name}`,
        to: `concept:${flow.input.concept}`,
        label: `input:${flow.input.mode ?? "create"}`
      });
    }
  }

  for (const capability of document.capabilities ?? []) {
    nodes.push({
      id: `capability:${capability.name}`,
      label: capability.name,
      kind: "capability",
      summary: capability.type
    });
  }

  for (const rule of document.orchestrationRules ?? []) {
    for (const action of rule.actions ?? []) {
      if (action.capability) {
        edges.push({
          from: `flow:${rule.name}`,
          to: `capability:${action.capability}`,
          label: `orchestration:${action.type}`
        });
      }
      if (action.concept) {
        edges.push({
          from: `flow:${rule.name}`,
          to: `concept:${action.concept}`,
          label: `orchestration:${action.type}`
        });
      }
    }
  }

  for (const event of document.events ?? []) {
    nodes.push({
      id: `event:${event.name}`,
      label: event.name,
      kind: "event",
      summary: `${event.payload?.length ?? 0} payload fields`
    });
  }

  if (config?.metadata?.capabilityBindings) {
    for (const binding of config.metadata.capabilityBindings) {
      if (binding.capability) {
        edges.push({
          from: `capability:${binding.capability}`,
          to: `capability:${binding.capability}`,
          label: `binding:${binding.target || binding.mode}`
        });
      }
    }
  }

  return { nodes, edges, erTables, erRelationships };
}

function truthRank(level?: string): number {
  if (!level) {
    return 1;
  }
  const match = /^T([0-6])$/i.exec(level.trim());
  return match ? Number.parseInt(match[1], 10) : 1;
}
