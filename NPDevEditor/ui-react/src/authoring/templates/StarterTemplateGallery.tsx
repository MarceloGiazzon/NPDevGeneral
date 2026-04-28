import React from "react";
import type { OfficialSampleCard } from "../services/modelLoader";
import type { StarterTemplateCard, StarterTemplateId } from "./starterTemplates";

type StarterTemplateGalleryProps = {
  starterTemplates: StarterTemplateCard[];
  officialSamples: OfficialSampleCard[];
  selectedTemplateId?: StarterTemplateId;
  selectedSampleId?: string;
  onChooseCanonicalDemo: () => void;
  onChooseOfficialSample: (sampleId: string) => void;
  onChooseStarterTemplate: (templateId: StarterTemplateId) => void;
};

export default function StarterTemplateGallery({
  starterTemplates,
  officialSamples,
  selectedTemplateId,
  selectedSampleId,
  onChooseCanonicalDemo,
  onChooseOfficialSample,
  onChooseStarterTemplate
}: StarterTemplateGalleryProps): JSX.Element {
  return (
    <div className="authoring-template-stack">
      <div className="authoring-template-grid">
        <article className="authoring-template-card is-guided">
          <header className="authoring-template-card__header">
            <div>
              <h4>Canonical Demo</h4>
              <p>The safest guided starter and the canonical reference specimen.</p>
            </div>
            <span>Recommended</span>
          </header>
          <p>
            Choose this when you want the strongest documentation alignment, the clearest walkthrough, and the
            least ambiguity about how NPDev is meant to behave.
          </p>
          <div className="authoring-chip-row">
            <span className="authoring-chip">canonical demo</span>
            <span className="authoring-chip">canonical baseline</span>
            <span className="authoring-chip">best first run</span>
          </div>
          <button type="button" onClick={onChooseCanonicalDemo}>
            Open canonical guided starter
          </button>
        </article>

        {officialSamples.map((sample) => (
          <article
            key={sample.id}
            className={`authoring-template-card is-guided ${selectedSampleId === sample.id ? "is-selected" : ""}`}
          >
            <header className="authoring-template-card__header">
              <div>
                <h4>{sample.title}</h4>
                <p>{sample.description}</p>
              </div>
              <span>{sample.complexity}</span>
            </header>
            <p>Scenario focus: {sample.scenarioFocus}</p>
            <div className="authoring-chip-row">
              {sample.features.slice(0, 4).map((feature) => (
                <span key={feature} className="authoring-chip">
                  {feature}
                </span>
              ))}
            </div>
            <button type="button" onClick={() => onChooseOfficialSample(sample.id)}>
              {selectedSampleId === sample.id ? "Selected guided starter" : "Use guided sample"}
            </button>
          </article>
        ))}
      </div>

      <div className="authoring-template-grid">
        {starterTemplates.map((template) => (
          <article
            key={template.id}
            className={`authoring-template-card ${selectedTemplateId === template.id ? "is-selected" : ""}`}
          >
            <header className="authoring-template-card__header">
              <div>
                <h4>{template.title}</h4>
                <p>{template.description}</p>
              </div>
              <span>{template.complexity}</span>
            </header>
            <p>{template.useCase}</p>
            <div className="authoring-template-card__facts">
              <div>
                <strong>Good for</strong>
                <span>{template.goodFor}</span>
              </div>
            </div>
            <ul className="authoring-template-card__notes">
              {template.starterNotes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
            <button type="button" onClick={() => onChooseStarterTemplate(template.id)}>
              {selectedTemplateId === template.id ? "Selected template" : "Use starter template"}
            </button>
          </article>
        ))}
      </div>
    </div>
  );
}

