import React, { useMemo, useState } from "react";
import AuditTimelinePanel from "../AuditTimelinePanel";
import ModelEditorPanel from "../ModelEditorPanel";
import OperatorConsolePanel from "../OperatorConsolePanel";
import OrchestrationEditorPanel from "../OrchestrationEditorPanel";
import PanelErrorBoundary from "../PanelErrorBoundary";
import PluginProvenancePanel from "../PluginProvenancePanel";
import PluginRepositoryPanel from "../PluginRepositoryPanel";
import PromptHistoryPanel from "../PromptHistoryPanel";
import RuleEditorPanel from "../RuleEditorPanel";

type TabId =
  | "model-editor"
  | "rule-editor"
  | "orchestration-editor"
  | "operator-console"
  | "audit-timeline"
  | "prompt-history"
  | "plugin-provenance"
  | "plugin-repository";

type TabDef = {
  id: TabId;
  label: string;
};

const TABS: TabDef[] = [
  { id: "model-editor", label: "Model Editor" },
  { id: "rule-editor", label: "Rule Editor" },
  { id: "orchestration-editor", label: "Orchestration Editor" },
  { id: "operator-console", label: "Operator Console" },
  { id: "audit-timeline", label: "Audit + Timeline" },
  { id: "prompt-history", label: "Prompt History" },
  { id: "plugin-provenance", label: "Plugin Provenance" },
  { id: "plugin-repository", label: "Plugin Repository" }
];

function renderPanel(activeTab: TabId): JSX.Element {
  switch (activeTab) {
    case "model-editor":
      return <ModelEditorPanel />;
    case "rule-editor":
      return <RuleEditorPanel />;
    case "orchestration-editor":
      return <OrchestrationEditorPanel />;
    case "operator-console":
      return <OperatorConsolePanel />;
    case "audit-timeline":
      return <AuditTimelinePanel />;
    case "prompt-history":
      return <PromptHistoryPanel />;
    case "plugin-provenance":
      return <PluginProvenancePanel />;
    case "plugin-repository":
      return <PluginRepositoryPanel />;
    default:
      return <ModelEditorPanel />;
  }
}

type ReactWorkbenchAppProps = {
  onOpenAuthoringStudio: () => void;
};

export default function ReactWorkbenchApp({
  onOpenAuthoringStudio
}: ReactWorkbenchAppProps): JSX.Element {
  const [activeTab, setActiveTab] = useState<TabId>("model-editor");

  const activeTabLabel = useMemo(() => {
    return TABS.find((tab) => tab.id === activeTab)?.label ?? "Model Editor";
  }, [activeTab]);

  return (
    <div
      style={{
        minHeight: "100vh",
        background: "#f6f8fa",
        color: "#1f2328",
        fontFamily: "Arial, Helvetica, sans-serif"
      }}
    >
      <header
        style={{
          padding: "20px 24px",
          borderBottom: "1px solid #d0d7de",
          background: "#fff"
        }}
      >
        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: 12,
            alignItems: "center",
            justifyContent: "space-between",
            marginBottom: 16
          }}
        >
          <div
            style={{
              padding: 12,
              borderRadius: 12,
              border: "1px solid #d0d7de",
              background: "#fff8c5",
              flex: "1 1 620px"
            }}
          >
            <div style={{ fontWeight: 700, marginBottom: 6 }}>Promotion-controlled UI surface</div>
            <div style={{ color: "#57606a", fontSize: 14 }}>
              Current canonical operator UI: /npdev-ui/. Alternate surface: /npdev-ui-react/.
              This React surface is currently marked as internal-workbench. Promotion must happen through
              canonical-ui-selection.json and npdev-set-canonical-ui-surface.ps1.
            </div>
          </div>

          <button
            type="button"
            onClick={onOpenAuthoringStudio}
            style={{
              padding: "12px 16px",
              borderRadius: 12,
              border: "1px solid #1f6feb",
              background: "#1f6feb",
              color: "#fff",
              fontWeight: 700,
              minWidth: 190
            }}
          >
            Open Authoring Studio
          </button>
        </div>

        <h1 style={{ margin: 0, marginBottom: 8 }}>NPDev React Workbench</h1>
        <p style={{ margin: 0, color: "#57606a" }}>
          Guided workspace, editors, operator views, and runtime governance surfaces.
        </p>
      </header>

      <nav
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 8,
          padding: 16,
          borderBottom: "1px solid #d0d7de",
          background: "#fff"
        }}
      >
        {TABS.map((tab) => {
          const active = tab.id === activeTab;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setActiveTab(tab.id)}
              style={{
                padding: "10px 14px",
                borderRadius: 999,
                border: active ? "1px solid #1f6feb" : "1px solid #d0d7de",
                background: active ? "#1f6feb" : "#fff",
                color: active ? "#fff" : "#1f2328",
                cursor: "pointer",
                fontWeight: 600
              }}
            >
              {tab.label}
            </button>
          );
        })}
      </nav>

      <main style={{ padding: 16 }}>
        <div
          style={{
            marginBottom: 12,
            color: "#57606a",
            fontSize: 14
          }}
        >
          Current panel: <strong>{activeTabLabel}</strong>
        </div>

        <div
          style={{
            border: "1px solid #d0d7de",
            borderRadius: 16,
            background: "#fff",
            overflow: "hidden"
          }}
        >
          <PanelErrorBoundary key={activeTab} panelLabel={activeTabLabel}>
            {renderPanel(activeTab)}
          </PanelErrorBoundary>
        </div>
      </main>
    </div>
  );
}
