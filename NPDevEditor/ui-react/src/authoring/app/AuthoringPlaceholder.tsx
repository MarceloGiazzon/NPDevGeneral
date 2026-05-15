import { AUTHORING_ROUTES } from "../routes/authoringRoutes";

type AuthoringPlaceholderProps = {
  title?: string;
  summary?: string;
  checklist?: string[];
};

export default function AuthoringPlaceholder({
  title = "Authoring shell",
  summary = "The route exists and is ready for future steps.",
  checklist = AUTHORING_ROUTES.map((entry) => `${entry.label}: ${entry.summary}`)
}: AuthoringPlaceholderProps): JSX.Element {
  return (
    <div className="authoring-route-card">
      <header className="authoring-route-card__header">
        <div>
          <h3>{title}</h3>
          <p>{summary}</p>
        </div>
        <div className="authoring-badge">Step 31 scaffold</div>
      </header>

      <ul className="authoring-checklist">
        {checklist.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  );
}
