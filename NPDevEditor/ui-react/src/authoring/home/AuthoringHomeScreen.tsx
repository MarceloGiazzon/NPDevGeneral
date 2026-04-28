import React from "react";
import ContextualHelpPanel from "../onboarding/ContextualHelpPanel";
import type { AuthoringCategoryCard, OfficialSampleCard, StarterTemplateCard, StarterTemplateId } from "../services/modelLoader";
import { START_HERE_PATHS } from "../navigation/authoringStep48Ux";
import StarterTemplateGallery from "../templates/StarterTemplateGallery";

type AuthoringHomeScreenProps = {
  categories: AuthoringCategoryCard[];
  activeCategoryId: string;
  officialSamples: OfficialSampleCard[];
  starterTemplates: StarterTemplateCard[];
  selectedSampleId?: string;
  selectedTemplateId?: StarterTemplateId;
  onChooseCategory: (categoryId: AuthoringCategoryCard["id"]) => void;
  onChooseSample: (sampleId: string) => void;
  onChooseTemplate: (templateId: StarterTemplateId) => void;
  onOpenChooser: () => void;
  onContinueToEditor: () => void;
};

export default function AuthoringHomeScreen({
  categories,
  activeCategoryId,
  officialSamples,
  starterTemplates,
  selectedSampleId,
  selectedTemplateId,
  onChooseCategory,
  onChooseSample,
  onChooseTemplate,
  onOpenChooser,
  onContinueToEditor
}: AuthoringHomeScreenProps): JSX.Element {
  const startHereActions = {
    "canonical-demo": () => {
      onChooseCategory("canonical-demo");
      onContinueToEditor();
    },
    "official-samples": () => {
      onChooseCategory("official-samples");
      onOpenChooser();
    },
    "new-model": () => {
      onChooseCategory("new-model");
      onContinueToEditor();
    }
  } as const;

  return (
    <div className="authoring-home">
      <section className="authoring-home__start-here" aria-label="Start here">
        <div className="authoring-editor-section__header">
          <div>
            <div className="authoring-badge">Start here</div>
            <h3>Three safe ways to begin</h3>
            <p>
              If you are new to NPDev, start with one of these guided entry paths before exploring the wider authoring shell.
            </p>
          </div>
        </div>

        <div className="authoring-start-grid">
          {START_HERE_PATHS.map((path) => {
            const selected = activeCategoryId === path.id;
            return (
              <article key={path.id} className={`authoring-start-card ${selected ? "is-selected" : ""}`}>
                <div className="authoring-start-card__header">
                  <div>
                    <h4>{path.title}</h4>
                    <p>{path.description}</p>
                  </div>
                  {path.recommended ? <span>Recommended</span> : null}
                </div>
                <small>{path.routeHint}</small>
                <button type="button" onClick={startHereActions[path.id]}>
                  {path.actionLabel}
                </button>
              </article>
            );
          })}
        </div>
      </section>

      <section className="authoring-home__hero">
        <div>
          <div className="authoring-badge">Step 32 home</div>
          <h3>Choose how you want to begin</h3>
          <p>
            NPDev supports multiple entry modes for different confidence levels. The safest beginner path is
            the canonical demo, official samples help you learn through smaller scenarios, and the other
            modes leave more freedom once you are ready.
          </p>
        </div>

        <div className="authoring-home__hero-actions">
          <button type="button" onClick={onOpenChooser}>
            Open detailed chooser
          </button>
          <button type="button" className="authoring-secondary-inline" onClick={onContinueToEditor}>
            Continue with current selection
          </button>
        </div>
      </section>

      <section className="authoring-home__grid">
        {categories.map((category) => {
          const active = category.id === activeCategoryId;
          return (
            <article key={category.id} className={`authoring-category-card ${active ? "is-selected" : ""}`}>
              <header className="authoring-category-card__header">
                <div>
                  <h4>{category.title}</h4>
                  <p>{category.description}</p>
                </div>
                <span className={`authoring-category-card__tone ${category.beginnerSafe ? "is-safe" : "is-advanced"}`}>
                  {category.beginnerSafe ? "Beginner-safe" : "Advanced"}
                </span>
              </header>

              <div className="authoring-category-card__facts">
                <div>
                  <strong>Best for</strong>
                  <span>{category.bestFor}</span>
                </div>
                <div>
                  <strong>Watch for</strong>
                  <span>{category.caution}</span>
                </div>
                <div>
                  <strong>Route hint</strong>
                  <span>{category.routeHint}</span>
                </div>
              </div>

              <footer className="authoring-category-card__footer">
                <button type="button" onClick={() => onChooseCategory(category.id)}>
                  {active ? "Selected" : "Use this path"}
                </button>
              </footer>
            </article>
          );
        })}
      </section>

      <section className="authoring-editor-section">
        <div className="authoring-editor-section__header">
          <div>
            <h3>Guided starters and templates</h3>
            <p>
              Beginners should not have to invent the first structure alone. Guided starters explain the difference
              between the canonical demo, official samples, and fresh starter templates.
            </p>
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

      <section className="authoring-onboarding-panel">
        <div>
          <h4>Beginner guidance</h4>
          <p>
            If you are not sure where to start, use the canonical demo first. It matches the canonical frozen reference specimen and gives the clearest walkthrough into the editor.
          </p>
        </div>

        <div>
          <h4>When to use official samples</h4>
          <p>
            Official samples are the next safest option when you want a narrower scenario than the full
            canonical demo.
          </p>
        </div>

        <div>
          <h4>When to bring your own model</h4>
          <p>
            Arbitrary-model and import flows are better once you already understand the category system and
            want to work from your own source files.
          </p>
        </div>
      </section>

      <ContextualHelpPanel
        title="Onboarding help and glossary hooks"
        summary="Use these definitions when the shell starts making sense structurally but the NPDev words still feel unfamiliar."
        tips={[
          "Canonical demo is the safest first run because the docs and current samples align around it.",
          "Official samples are guided starters too, but each teaches one narrower scenario.",
          "Starter templates are for your own fresh draft when you want structure without inheriting platform-owned demo content."
        ]}
        glossaryHookIds={["canonical-demo", "official-sample", "starter-template", "concept", "flow"]}
      />
    </div>
  );
}

