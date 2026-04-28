import React, { useState } from "react";

type ExplainabilityTooltipProps = {
  title: string;
  detail: string;
};

export default function ExplainabilityTooltip({
  title,
  detail
}: ExplainabilityTooltipProps): JSX.Element {
  const [open, setOpen] = useState<boolean>(false);

  return (
    <span className="authoring-help-inline">
      <button
        type="button"
        className={`authoring-help-toggle ${open ? "is-selected" : ""}`}
        onClick={() => setOpen((value) => !value)}
        aria-label={title}
        title={title}
      >
        ?
      </button>
      {open ? (
        <span className="authoring-help-inline__panel">
          <strong>{title}</strong>
          <small>{detail}</small>
        </span>
      ) : null}
    </span>
  );
}
