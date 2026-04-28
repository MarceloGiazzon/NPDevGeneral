import React from "react";
import type { AuthoringConfigDocument } from "../../config/configDocumentTypes";
import { joinTextList, parseTextList } from "../editorUtils";

type DatabaseRuntimeConfigSectionProps = {
  document: AuthoringConfigDocument;
  onChange: (document: AuthoringConfigDocument) => void;
};

export default function DatabaseRuntimeConfigSection({
  document,
  onChange
}: DatabaseRuntimeConfigSectionProps): JSX.Element {
  return (
    <section className="authoring-editor-section">
      <div className="authoring-editor-section__header">
        <div>
          <h3>Runtime and persistence</h3>
          <p>Set database defaults, runtime port/profile values, and launch-time Java arguments.</p>
        </div>
      </div>

      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>Database defaults</strong>
        </div>
        <div className="authoring-form-grid">
          <label>
            Provider
            <select
              value={document.database.provider}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    provider: event.target.value as "docker-postgres" | "postgres"
                  }
                })
              }
            >
              <option value="docker-postgres">docker-postgres</option>
              <option value="postgres">postgres</option>
            </select>
          </label>

          <label>
            Host
            <input
              value={document.database.host}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    host: event.target.value
                  }
                })
              }
            />
          </label>

          <label>
            Port
            <input
              type="number"
              value={document.database.port}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    port: Number(event.target.value)
                  }
                })
              }
            />
          </label>

          <label>
            Database
            <input
              value={document.database.database}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    database: event.target.value
                  }
                })
              }
            />
          </label>

          <label>
            Username
            <input
              value={document.database.username}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    username: event.target.value
                  }
                })
              }
            />
          </label>

          <label>
            Password
            <input
              value={document.database.password}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    password: event.target.value
                  }
                })
              }
            />
          </label>

          <label>
            Admin database
            <input
              value={document.database.adminDatabase}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    adminDatabase: event.target.value
                  }
                })
              }
            />
          </label>

          <label>
            Reset mode
            <select
              value={document.database.resetMode}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    resetMode: event.target.value as "reset" | "preserve"
                  }
                })
              }
            >
              <option value="reset">reset</option>
              <option value="preserve">preserve</option>
            </select>
          </label>

          <label>
            Container name
            <input
              value={document.database.containerName ?? ""}
              onChange={(event) =>
                onChange({
                  ...document,
                  database: {
                    ...document.database,
                    containerName: event.target.value || undefined
                  }
                })
              }
            />
          </label>
        </div>
      </div>

      <div className="authoring-subcard">
        <div className="authoring-editor-section__miniheader">
          <strong>Runtime environment</strong>
        </div>
        <div className="authoring-form-grid">
          <label>
            Spring profile
            <input
              value={document.runtime.springProfile}
              onChange={(event) =>
                onChange({
                  ...document,
                  runtime: {
                    ...document.runtime,
                    springProfile: event.target.value
                  }
                })
              }
            />
          </label>

          <label>
            Server port
            <input
              type="number"
              value={document.runtime.serverPort}
              onChange={(event) =>
                onChange({
                  ...document,
                  runtime: {
                    ...document.runtime,
                    serverPort: Number(event.target.value)
                  }
                })
              }
            />
          </label>

          <label>
            Gradle task
            <select
              value={document.runtime.gradleTask}
              onChange={(event) =>
                onChange({
                  ...document,
                  runtime: {
                    ...document.runtime,
                    gradleTask: event.target.value as "bootRun"
                  }
                })
              }
            >
              <option value="bootRun">bootRun</option>
            </select>
          </label>

          <label>
            Java args
            <input
              value={joinTextList(document.runtime.javaArgs)}
              onChange={(event) =>
                onChange({
                  ...document,
                  runtime: {
                    ...document.runtime,
                    javaArgs: parseTextList(event.target.value)
                  }
                })
              }
            />
          </label>
        </div>
      </div>
    </section>
  );
}
