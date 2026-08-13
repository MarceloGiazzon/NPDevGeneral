import ConceptsEditorSection from "./concepts/ConceptsEditorSection";
import DomainTypesEditorSection from "./domainTypes/DomainTypesEditorSection";
import FieldsEditorSection from "./fields/FieldsEditorSection";
import EnumsEditorSection from "./enums/EnumsEditorSection";
import ReferencesEditorSection from "./references/ReferencesEditorSection";
import InvariantsEditorSection from "./invariants/InvariantsEditorSection";
import FlowsEditorSection from "./flows/FlowsEditorSection";
import QueriesEditorSection from "./queries/QueriesEditorSection";
import RuleProfilesEditorSection from "./ruleProfiles/RuleProfilesEditorSection";
import ProceduresEditorSection from "./procedures/ProceduresEditorSection";
import PanelsEditorSection from "./panels/PanelsEditorSection";
import StateMachinesEditorSection from "./stateMachines/StateMachinesEditorSection";
import ActionsEditorSection from "./actions/ActionsEditorSection";
import MetadataEditorSection from "./metadata/MetadataEditorSection";
import type {
  AuthoringEntity,
  AuthoringField,
  AuthoringFlow,
  AuthoringLifecycleTransition,
  AuthoringModelDocument,
  AuthoringOrchestrationRule
} from "./modelDocumentTypes";
import { moveItem, updateEntity } from "./editorUtils";

type ModelEditorFormSectionsProps = {
  document: AuthoringModelDocument;
  concepts: AuthoringEntity[];
  conceptNames: string[];
  selectedEntity: AuthoringEntity | null;
  flows: AuthoringFlow[];
  queries: AuthoringModelDocument["queries"];
  ruleProfiles: AuthoringModelDocument["ruleProfiles"];
  procedures: AuthoringModelDocument["procedures"];
  panels: AuthoringModelDocument["panels"];
  orchestrationRules: AuthoringOrchestrationRule[];
  focusedFieldName?: string | null;
  focusedSection?: string | null;
  onSelectConcept: (conceptName: string) => void;
  replaceDocument: (document: AuthoringModelDocument) => void;
  updateSelectedEntity: (updater: (entity: AuthoringEntity) => AuthoringEntity) => void;
  updateSelectedField: (fieldName: string, updater: (field: AuthoringField) => AuthoringField) => void;
  updateFlows: (flows: AuthoringFlow[]) => void;
  updateQueries: (queries: AuthoringModelDocument["queries"]) => void;
  updateRuleProfiles: (ruleProfiles: AuthoringModelDocument["ruleProfiles"]) => void;
  updateProcedures: (procedures: AuthoringModelDocument["procedures"]) => void;
  updatePanels: (panels: AuthoringModelDocument["panels"]) => void;
  updateOrchestrationRules: (rules: AuthoringOrchestrationRule[]) => void;
  updateEntityTransitions: (transitions: AuthoringLifecycleTransition[]) => void;
};

export default function ModelEditorFormSections({
  document,
  concepts,
  conceptNames,
  selectedEntity,
  flows,
  queries,
  ruleProfiles,
  procedures,
  panels,
  orchestrationRules,
  focusedFieldName,
  focusedSection,
  onSelectConcept,
  replaceDocument,
  updateSelectedEntity,
  updateSelectedField,
  updateFlows,
  updateQueries,
  updateRuleProfiles,
  updateProcedures,
  updatePanels,
  updateOrchestrationRules,
  updateEntityTransitions
}: ModelEditorFormSectionsProps): JSX.Element {
  return (
    <>
      <ConceptsEditorSection
        entities={concepts}
        selectedConceptName={selectedEntity?.name ?? null}
        onSelectConcept={onSelectConcept}
        onAddConcept={() => {
          const nextName = `Concept${concepts.length + 1}`;
          replaceDocument({
            ...document,
            concepts: [
              ...concepts,
              {
                name: nextName,
                ui: {
                  label: nextName
                },
                fields: [
                  {
                    name: "id",
                    type: "uuid",
                    id: true,
                    required: true
                  }
                ],
                invariants: []
              }
            ]
          });
          onSelectConcept(nextName);
        }}
        onRemoveConcept={(conceptName) =>
          replaceDocument({
            ...document,
            concepts: concepts.filter((entity) => entity.name !== conceptName)
          })
        }
        onMoveConcept={(conceptName, direction) => {
          const conceptIndex = concepts.findIndex((entity) => entity.name === conceptName);
          if (conceptIndex >= 0) {
            replaceDocument({
              ...document,
              concepts: moveItem(concepts, conceptIndex, direction)
            });
          }
        }}
        onUpdateConcept={(conceptName, updater) => replaceDocument(updateEntity(document, conceptName, updater))}
      />

      <DomainTypesEditorSection
        domainTypes={document.domainTypes}
        onChange={(domainTypes) =>
          replaceDocument({
            ...document,
            domainTypes
          })
        }
      />

      <FieldsEditorSection
        entity={selectedEntity}
        requestedFieldName={focusedSection === "fields" ? focusedFieldName ?? null : null}
        onUpdateField={updateSelectedField}
        onAddField={() => {
          if (!selectedEntity) {
            return;
          }
          updateSelectedEntity((entity) => ({
            ...entity,
            fields: [
              ...entity.fields,
              {
                name: `field${entity.fields.length + 1}`,
                type: "string"
              }
            ]
          }));
        }}
        onRemoveField={(fieldName) => {
          if (!selectedEntity) {
            return;
          }
          updateSelectedEntity((entity) => ({
            ...entity,
            fields: entity.fields.filter((field) => field.name !== fieldName)
          }));
        }}
        onMoveField={(fieldName, direction) => {
          if (!selectedEntity) {
            return;
          }
          const fieldIndex = selectedEntity.fields.findIndex((field) => field.name === fieldName);
          if (fieldIndex >= 0) {
            updateSelectedEntity((entity) => ({
              ...entity,
              fields: moveItem(entity.fields, fieldIndex, direction)
            }));
          }
        }}
      />

      <EnumsEditorSection entity={selectedEntity} onUpdateField={updateSelectedField} />
      <ReferencesEditorSection
        entity={selectedEntity}
        allEntities={concepts}
        requestedFieldName={focusedSection === "references" ? focusedFieldName ?? null : null}
        onUpdateField={updateSelectedField}
      />

      <InvariantsEditorSection
        entity={selectedEntity}
        onChange={(invariants) =>
          updateSelectedEntity((entity) => ({
            ...entity,
            invariants
          }))
        }
      />

      <QueriesEditorSection queries={queries} conceptNames={conceptNames} onChange={updateQueries} />
      <RuleProfilesEditorSection ruleProfiles={ruleProfiles} onChange={updateRuleProfiles} />
      <ProceduresEditorSection
        procedures={procedures}
        conceptNames={conceptNames}
        queryNames={queries.map((query) => query.name)}
        procedureNames={procedures.map((procedure) => procedure.name)}
        onChange={updateProcedures}
      />
      <PanelsEditorSection
        panels={panels}
        conceptNames={conceptNames}
        procedureNames={procedures.map((procedure) => procedure.name)}
        flowNames={flows.map((flow) => flow.name)}
        onChange={updatePanels}
      />

      <FlowsEditorSection flows={flows} onChange={updateFlows} />
      <StateMachinesEditorSection
        entity={selectedEntity}
        onChangeStates={(states) =>
          updateSelectedEntity((entity) => ({
            ...entity,
            lifecycle: {
              ...entity.lifecycle,
              states
            }
          }))
        }
        onChangeTransitions={updateEntityTransitions}
      />
      <ActionsEditorSection
        entity={selectedEntity}
        flows={flows}
        orchestrationRules={orchestrationRules}
        onChangeFlows={updateFlows}
        onChangeEntityTransitions={updateEntityTransitions}
        onChangeOrchestrationRules={updateOrchestrationRules}
      />
      <MetadataEditorSection
        document={document}
        entity={selectedEntity}
        onChangeDocument={replaceDocument}
        onUpdateField={updateSelectedField}
      />
    </>
  );
}
