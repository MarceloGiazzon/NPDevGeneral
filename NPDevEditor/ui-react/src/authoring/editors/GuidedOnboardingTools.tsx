import type { AuthoringEntity, AuthoringField, AuthoringFlow } from "./modelDocumentTypes";
import ConceptCreationWizard from "../onboarding/ConceptCreationWizard";
import ReferenceWizard from "../onboarding/ReferenceWizard";
import FlowCreationWizard from "../onboarding/FlowCreationWizard";
import ContextualHelpPanel from "../onboarding/ContextualHelpPanel";
import ExplainabilityTooltip from "../help/ExplainabilityTooltip";

type GuidedOnboardingToolsProps = {
  concepts: AuthoringEntity[];
  selectedConceptName: string | null;
  onCreateConcept: (entity: AuthoringEntity) => void;
  onCreateReference: (sourceConceptName: string, field: AuthoringField) => void;
  onCreateFlow: (flow: AuthoringFlow) => void;
};

export default function GuidedOnboardingTools({
  concepts,
  selectedConceptName,
  onCreateConcept,
  onCreateReference,
  onCreateFlow
}: GuidedOnboardingToolsProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Guided onboarding tools</h3>
          <p>
            Step 39 adds guided templates, lightweight creation wizards, and glossary-driven help so empty or
            early-stage drafts stop feeling directionless.
          </p>
        </div>
        <ExplainabilityTooltip
          title="Why guided tools stay in the editor"
          detail="NPDev tries to keep modeling decisions understandable at the moment they are made, not only after export or runtime."
        />
      </div>

      <div className="authoring-wizard-grid">
        <ConceptCreationWizard conceptCount={concepts.length} onCreateConcept={onCreateConcept} />
        <ReferenceWizard
          entities={concepts}
          selectedConceptName={selectedConceptName}
          onCreateReference={onCreateReference}
        />
        <FlowCreationWizard entities={concepts} onCreateFlow={onCreateFlow} />
      </div>

      <ContextualHelpPanel
        title="Model-editor glossary hooks"
        summary="Use these quick explanations when you understand the forms mechanically but still want help with NPDev vocabulary."
        tips={[
          "Create concepts first, then add references once at least two concepts exist.",
          "Queries, procedures, and panels are now first-class peers of flows, not side notes.",
          "Starter templates are meant to be edited freely after the first load."
        ]}
        glossaryHookIds={["concept", "reference", "flow", "invariant", "capability"]}
      />
    </section>
  );
}
