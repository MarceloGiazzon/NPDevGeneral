import type { AuthoringModelDocument } from "./modelDocumentTypes";
import type { JsonValidationIssue } from "../json/jsonEditorTypes";

function hasText(value: string | undefined | null): boolean {
  return Boolean(value && value.trim().length > 0);
}

export function validateModelDocument(document: AuthoringModelDocument): JsonValidationIssue[] {
  const issues: JsonValidationIssue[] = [];

  if (!hasText(document.namespace)) {
    issues.push({
      severity: "error",
      path: "namespace",
      message: "Namespace is required."
    });
  }

  if (!hasText(document.version)) {
    issues.push({
      severity: "error",
      path: "version",
      message: "Model version is required."
    });
  }

  if ((document.concepts ?? []).length === 0) {
    issues.push({
      severity: "error",
      path: "concepts",
      message: "At least one concept is required."
    });
  }

  const entityNames = new Set<string>();
  (document.concepts ?? []).forEach((entity, entityIndex) => {
    const entityPath = `concepts[${entityIndex}]`;
    if (!hasText(entity.name)) {
      issues.push({
        severity: "error",
        path: `${entityPath}.name`,
        message: "Concept name is required."
      });
    } else if (entityNames.has(entity.name)) {
      issues.push({
        severity: "error",
        path: `${entityPath}.name`,
        message: `Duplicate concept name '${entity.name}'.`
      });
    } else {
      entityNames.add(entity.name);
    }

    const fieldNames = new Set<string>();
    (entity.fields ?? []).forEach((field, fieldIndex) => {
      const fieldPath = `${entityPath}.fields[${fieldIndex}]`;
      if (!hasText(field.name)) {
        issues.push({
          severity: "error",
          path: `${fieldPath}.name`,
          message: "Field name is required."
        });
      } else if (fieldNames.has(field.name)) {
        issues.push({
          severity: "error",
          path: `${fieldPath}.name`,
          message: `Duplicate field name '${field.name}' inside ${entity.name || "concept"}.`
        });
      } else {
        fieldNames.add(field.name);
      }

      if (!hasText(field.type)) {
        issues.push({
          severity: "warning",
          path: `${fieldPath}.type`,
          message: "Field type is empty. A concrete type is recommended."
        });
      }

      if (field.type === "reference" && !hasText(field.reference?.target)) {
        issues.push({
          severity: "error",
          path: `${fieldPath}.reference.target`,
          message: "Reference fields should declare a target concept."
        });
      }

      if (field.type === "enum" && (field.enumValues ?? []).length === 0) {
        issues.push({
          severity: "warning",
          path: `${fieldPath}.enumValues`,
          message: "Enum fields should declare at least one enum option."
        });
      }
    });
  });

  const knownConcepts = new Set((document.concepts ?? []).map((entry) => entry.name));

  (document.queries ?? []).forEach((query, queryIndex) => {
    if (!hasText(query.name)) {
      issues.push({
        severity: "error",
        path: `queries[${queryIndex}].name`,
        message: "Query name is required."
      });
    }
    if (!hasText(query.concept)) {
      issues.push({
        severity: "error",
        path: `queries[${queryIndex}].concept`,
        message: "Queries should reference a concept."
      });
    } else if (!knownConcepts.has(query.concept)) {
      issues.push({
        severity: "warning",
        path: `queries[${queryIndex}].concept`,
        message: `Query concept '${query.concept}' is not defined in this model.`
      });
    }
  });

  (document.ruleProfiles ?? []).forEach((profile, profileIndex) => {
    if (!hasText(profile.name)) {
      issues.push({
        severity: "error",
        path: `ruleProfiles[${profileIndex}].name`,
        message: "Rule profile name is required."
      });
    }
  });

  (document.procedures ?? []).forEach((procedure, procedureIndex) => {
    if (!hasText(procedure.name)) {
      issues.push({
        severity: "error",
        path: `procedures[${procedureIndex}].name`,
        message: "Procedure name is required."
      });
    }
    if ((procedure.steps ?? []).length === 0) {
      issues.push({
        severity: "error",
        path: `procedures[${procedureIndex}].steps`,
        message: "Procedures should declare at least one step."
      });
    }
  });

  (document.panels ?? []).forEach((panel, panelIndex) => {
    if (!hasText(panel.name)) {
      issues.push({
        severity: "error",
        path: `panels[${panelIndex}].name`,
        message: "Panel name is required."
      });
    }
    const hasConceptBoundDataSource = (panel.dataSources ?? []).some((dataSource) => hasText(dataSource.concept));
    if (!hasText(panel.route) && !hasConceptBoundDataSource) {
      issues.push({
        severity: "warning",
        path: `panels[${panelIndex}]`,
        message: "Panels should declare a route or at least one concept-bound data source."
      });
    }
  });

  return issues;
}
