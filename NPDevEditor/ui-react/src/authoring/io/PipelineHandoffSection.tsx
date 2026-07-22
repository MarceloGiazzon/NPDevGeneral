import type { AuthoringBundle } from "./bundleTypes";
import {
  buildPipelineCommandPreview,
  buildRecommendedHandoffDir,
  downloadPipelineHandoffPackage,
  savePipelineHandoffPackageToChosenDirectory,
  summarizePipelinePreflight
} from "../pipeline/pipelineHandoff";
import type { ValidationDiagnostic } from "../../types";

type PipelineHandoffSectionProps = {
  currentBundle: AuthoringBundle;
  bundleLabel: string;
  handoffDirHint: string;
  pipelineDiagnostics: ValidationDiagnostic[];
  pipelinePreflight: ReturnType<typeof summarizePipelinePreflight>;
  onChangeHandoffDirHint: (value: string) => void;
  onStatusMessage: (value: string) => void;
};

export default function PipelineHandoffSection({
  currentBundle,
  bundleLabel,
  handoffDirHint,
  pipelineDiagnostics,
  pipelinePreflight,
  onChangeHandoffDirHint,
  onStatusMessage
}: PipelineHandoffSectionProps): JSX.Element {
  const handoffDir = handoffDirHint || buildRecommendedHandoffDir(currentBundle);

  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Generation handoff to NP pipeline</h3>
          <p>
            This exports a handoff package for the normal NP projection path. It validates the current authoring
            bundle first, stages canonical files, and points to the standard PowerShell bridge without touching
            `FinalExec` directly.
          </p>
        </div>
      </div>

      <div className="authoring-form-grid">
        <label>
          Recommended handoff folder
          <input value={handoffDirHint} onChange={(event) => onChangeHandoffDirHint(event.target.value)} />
        </label>
        <label>
          Artifact output root
          <input value={currentBundle.config.artifact.root} readOnly />
        </label>
      </div>

      <div className="authoring-preview-grid">
        <article className="authoring-preview-card">
          <div className="authoring-preview-card__header">
            <strong>Preflight</strong>
            <span>{pipelinePreflight.ready ? "Ready" : "Blocked"}</span>
          </div>
          <p>
            {pipelinePreflight.ready
              ? "Model and config are clear enough to hand off into the normal NP export path."
              : "Fix blocking validation errors before creating a projection handoff package."}
          </p>
          <small>
            Errors: {pipelinePreflight.errorCount} | Warnings: {pipelinePreflight.warningCount}
          </small>
        </article>

        <article className="authoring-preview-card">
          <div className="authoring-preview-card__header">
            <strong>Normal NP command</strong>
            <span>Projection boundary preserved</span>
          </div>
          <pre className="json-pane small">
            {buildPipelineCommandPreview(handoffDir, currentBundle.config.artifact.root)}
          </pre>
        </article>
      </div>

      <div className="authoring-inline-actions">
        <button
          type="button"
          disabled={!pipelinePreflight.ready}
          onClick={() => {
            downloadPipelineHandoffPackage(currentBundle, bundleLabel, handoffDir, pipelineDiagnostics);
            onStatusMessage("Downloaded authoring handoff package for the normal NP pipeline.");
          }}
        >
          Download handoff package
        </button>
        <button
          type="button"
          className="authoring-secondary-inline"
          disabled={!pipelinePreflight.ready}
          onClick={async () => {
            const result = await savePipelineHandoffPackageToChosenDirectory(
              currentBundle,
              bundleLabel,
              handoffDir,
              pipelineDiagnostics
            );
            onStatusMessage(
              result === "saved"
                ? "Saved authoring handoff package to the chosen folder."
                : "Chosen-folder save is not supported here. Use download instead."
            );
          }}
        >
          Save handoff package to folder
        </button>
      </div>
    </section>
  );
}
