import React from "react";
import ContextualHelpPanel from "../../onboarding/ContextualHelpPanel";
import StarterTemplateGallery from "../../templates/StarterTemplateGallery";
import type { AuthoringCategoryCard, OfficialSampleCard, StarterTemplateCard, StarterTemplateId } from "../../services/modelLoader";

type ModelChooserScreenProps = {
  categories: AuthoringCategoryCard[];
  selectedCategoryId: string;
  officialSamples: OfficialSampleCard[];
  selectedSampleId?: string;
  starterTemplates: StarterTemplateCard[];
  selectedTemplateId?: StarterTemplateId;
  onChooseCategory: (categoryId: AuthoringCategoryCard["id"]) => void;
  onChooseSample: (sampleId: string) => void;
  onChooseTemplate: (templateId: StarterTemplateId) => void;
  onContinueToEditor: () => void;
};

export default function ModelChooserScreen({
  categories,
  selectedCategoryId,
  officialSamples,
  selectedSampleId,
  starterTemplates,
  selectedTemplateId,
  onChooseCategory,
  onChooseSample,
  onChooseTemplate,
  onContinueToEditor
}: ModelChooserScreenProps): JSX.Element {
  return (
    <div className="authoring-chooser">
      <section className="authoring-chooser__intro">
        <div>
          <div className="authoring-badge">Category-aware chooser</div>
          <h3>Pick the safest entry mode for this session</h3>
          <p>
            The chooser keeps platform-owned lanes explicit: canonical demo, official samples, and
            open-ended user paths stay separate so onboarding and experimentation do not drift into one
            another.
          </p>
        </div>

        <button type="button" onClick={onContinueToEditor}>
          Continue into editor shell
        </button>
      </section>

      <section className="authoring-chooser__category-grid">
        {categories.map((category) => {
          const active = category.id === selectedCategoryId;
          return (
            <article key={category.id} className={`authoring-chooser-card ${active ? "is-selected" : ""}`}>
              <header>
                <h4>{category.title}</h4>
                <span>{category.beginnerSafe ? "Recommended start" : "Flexible path"}</span>
              </header>
              <p>{category.description}</p>
              <ul className="authoring-inline-list">
                <li>{category.bestFor}</li>
                <li>{category.routeHint}</li>
              </ul>
              <button type="button" onClick={() => onChooseCategory(category.id)}>
                {active ? "Current path" : "Select path"}
              </button>
            </article>
          );
        })}
      </section>

      <section className="authoring-chooser__samples">
        <div className="authoring-panel__header">
          <div>
            <h3>Official sample catalog</h3>
            <p>Curated samples are especially helpful once you want a guided scenario smaller than the canonical demo.</p>
          </div>
        </div>

        <div className="authoring-sample-grid">
          {officialSamples.map((sample) => {
            const active = selectedSampleId === sample.id;
            return (
              <article key={sample.id} className={`authoring-sample-card ${active ? "is-selected" : ""}`}>
                <header className="authoring-sample-card__header">
                  <div>
                    <h4>{sample.title}</h4>
                    <p>{sample.description}</p>
                  </div>
                  <span>{sample.complexity}</span>
                </header>

                <div className="authoring-sample-card__meta">
                  <strong>Scenario focus</strong>
                  <span>{sample.scenarioFocus}</span>
                </div>

                <div className="authoring-sample-card__stack">
                  <div>
                    <strong>Learning goals</strong>
                    <ul>
                      {sample.learningGoals.map((goal) => (
                        <li key={goal}>{goal}</li>
                      ))}
                    </ul>
                  </div>

                  <div>
                    <strong>Expected outcomes</strong>
                    <ul>
                      {sample.expectedOutcomes.map((outcome) => (
                        <li key={outcome}>{outcome}</li>
                      ))}
                    </ul>
                  </div>
                </div>

                <div className="authoring-sample-card__features">
                  {sample.features.map((feature) => (
                    <span key={feature}>{feature}</span>
                  ))}
                </div>

                <button type="button" onClick={() => onChooseSample(sample.id)}>
                  {active ? "Selected sample" : "Load this sample"}
                </button>
              </article>
            );
          })}
        </div>
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Starter template catalog</h3>
            <p>Fresh templates sit alongside canonical and sample starters so the differences are explicit before editing begins.</p>
          </div>
        </div>

        <StarterTemplateGallery
          starterTemplates={starterTemplates}
          officialSamples={officialSamples}
          selectedSampleId={selectedSampleId}
          selectedTemplateId={selectedTemplateId}
          onChooseCanonicalDemo={() => onChooseCategory("canonical-demo")}
          onChooseOfficialSample={onChooseSample}
          onChooseStarterTemplate={onChooseTemplate}
        />
      </section>

      <ContextualHelpPanel
        title="How to pick a safe starting point"
        summary="This chooser is here to lower the cost of the first decision, not increase it."
        tips={[
          "Use canonical demo when you want the highest confidence that docs, examples, and generated behavior line up.",
          "Use an official sample when you want a smaller teaching scenario with a narrower business shape.",
          "Use a starter template when you want your own draft, but you still want guided structure instead of a blank page."
        ]}
        glossaryHookIds={["canonical-demo", "official-sample", "starter-template", "reference", "flow"]}
      />
    </div>
  );
}
