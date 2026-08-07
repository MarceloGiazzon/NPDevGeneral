import React from "react";

type Props = {
  panelLabel: string;
  children: React.ReactNode;
};

type State = {
  error: Error | null;
};

/**
 * REG-139 layer 3: there was no componentDidCatch/ErrorBoundary/getDerivedStateFromError anywhere
 * in the editor before this, so a throw in any one panel took down the entire application -- the
 * root cause of the "blank page" was Layer 1's wrong shape, but this is what keeps the NEXT bug of
 * this class from being a blank page too. Layers 1 and 2 fix today's bug; this fixes the blast
 * radius of every future one. The caller keys this per active tab (see ReactWorkbenchApp) so
 * switching tabs always mounts a fresh boundary instead of getting stuck on a stale error.
 */
export default class PanelErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    console.error(`PanelErrorBoundary caught an error in panel "${this.props.panelLabel}":`, error, info.componentStack);
  }

  render(): React.ReactNode {
    const { error } = this.state;
    if (error) {
      return (
        <section className="panel" role="alert">
          <div className="section-header">
            <div>
              <h2>{this.props.panelLabel} failed to render</h2>
              <div className="hint">
                This panel hit an unexpected error. The rest of the workbench -- navigation, tabs,
                and header -- is still usable; try another tab or reload this one.
              </div>
            </div>
          </div>
          <div className="status-box error">{error.message || "Unknown error"}</div>
        </section>
      );
    }
    return this.props.children;
  }
}
