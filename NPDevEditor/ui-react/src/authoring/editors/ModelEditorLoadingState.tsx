export default function ModelEditorLoadingState(): JSX.Element {
  return (
    <div className="authoring-route-card">
      <div className="authoring-route-card__header">
        <div>
          <h3>Loading model editor</h3>
          <p>The authoring workspace is preparing a guided document session for the selected model source.</p>
        </div>
        <div className="authoring-badge">Preparing</div>
      </div>
    </div>
  );
}
