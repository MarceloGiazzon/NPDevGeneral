import React from "react";
import type { AuthoringConfigDocument } from "../../config/configDocumentTypes";

type ProjectionConfigSectionProps = {
  document: AuthoringConfigDocument;
  onChange: (document: AuthoringConfigDocument) => void;
};

function renderToggle(
  label: string,
  checked: boolean,
  onCheckedChange: (checked: boolean) => void
): JSX.Element {
  return (
    <label>
      <input type="checkbox" checked={checked} onChange={(event) => onCheckedChange(event.target.checked)} />
      {label}
    </label>
  );
}

export default function ProjectionConfigSection({
  document,
  onChange
}: ProjectionConfigSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Projection and generation</h3>
          <p>Guide generator behavior, bootstrap merge mode, and projected artifact/finalExec locations.</p>
        </div>
      </div>

      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>Generator switches</strong>
        </div>
        <div className="authoring-toggle-row">
          {renderToggle("Fail if model missing", document.generator.failIfModelMissing, (checked) =>
            onChange({
              ...document,
              generator: {
                ...document.generator,
                failIfModelMissing: checked
              }
            })
          )}
          {renderToggle("Fail if config missing", document.generator.failIfConfigMissing, (checked) =>
            onChange({
              ...document,
              generator: {
                ...document.generator,
                failIfConfigMissing: checked
              }
            })
          )}
          {renderToggle("Clean output before generate", document.generator.cleanOutputBeforeGenerate, (checked) =>
            onChange({
              ...document,
              generator: {
                ...document.generator,
                cleanOutputBeforeGenerate: checked
              }
            })
          )}
          {renderToggle("Emit plugin assets", document.generator.emitPluginAssets, (checked) =>
            onChange({
              ...document,
              generator: {
                ...document.generator,
                emitPluginAssets: checked
              }
            })
          )}
          {renderToggle("Emit runtime assets", document.generator.emitRuntimeAssets, (checked) =>
            onChange({
              ...document,
              generator: {
                ...document.generator,
                emitRuntimeAssets: checked
              }
            })
          )}
          {renderToggle("Emit UI assets", document.generator.emitUiAssets, (checked) =>
            onChange({
              ...document,
              generator: {
                ...document.generator,
                emitUiAssets: checked
              }
            })
          )}
        </div>
      </div>

      <div className="authoring-form-grid">
        <label>
          Bootstrap root
          <input
            value={document.bootstrap.root}
            onChange={(event) =>
              onChange({
                ...document,
                bootstrap: {
                  ...document.bootstrap,
                  root: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Merge strategy
          <select
            value={document.bootstrap.mergeStrategy}
            onChange={(event) =>
              onChange({
                ...document,
                bootstrap: {
                  ...document.bootstrap,
                  mergeStrategy: event.target.value as "clean-copy" | "robocopy-merge"
                }
              })
            }
          >
            <option value="clean-copy">clean-copy</option>
            <option value="robocopy-merge">robocopy-merge</option>
          </select>
        </label>

        <label>
          Artifact root
          <input
            value={document.artifact.root}
            onChange={(event) =>
              onChange({
                ...document,
                artifact: {
                  ...document.artifact,
                  root: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          FinalExec root
          <input
            value={document.finalExec.root}
            onChange={(event) =>
              onChange({
                ...document,
                finalExec: {
                  ...document.finalExec,
                  root: event.target.value
                }
              })
            }
          />
        </label>
      </div>

      <div className="authoring-form-grid">
        <label>
          Generated folder name
          <input
            value={document.artifact.generatedFolderName}
            onChange={(event) =>
              onChange({
                ...document,
                artifact: {
                  ...document.artifact,
                  generatedFolderName: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Libs folder name
          <input
            value={document.artifact.libsFolderName}
            onChange={(event) =>
              onChange({
                ...document,
                artifact: {
                  ...document.artifact,
                  libsFolderName: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Meta folder name
          <input
            value={document.artifact.metaFolderName}
            onChange={(event) =>
              onChange({
                ...document,
                artifact: {
                  ...document.artifact,
                  metaFolderName: event.target.value
                }
              })
            }
          />
        </label>

        <label>
          Delete before mount
          <select
            value={document.finalExec.deleteBeforeMount ? "true" : "false"}
            onChange={(event) =>
              onChange({
                ...document,
                finalExec: {
                  ...document.finalExec,
                  deleteBeforeMount: event.target.value === "true"
                }
              })
            }
          >
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        </label>
      </div>
    </section>
  );
}
