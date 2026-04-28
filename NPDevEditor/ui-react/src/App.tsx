import React, { Suspense, useEffect, useState } from "react";

const AuthoringApp = React.lazy(() => import("./authoring/app/AuthoringApp"));
const ReactWorkbenchApp = React.lazy(() => import("./workbench/ReactWorkbenchApp"));

type SurfaceId = "workbench" | "authoring";

function resolveSurface(hashValue: string): SurfaceId {
  const normalized = hashValue.replace(/^#/, "").trim().toLowerCase();
  if (normalized.startsWith("/authoring")) {
    return "authoring";
  }
  return "workbench";
}

function navigateTo(hashValue: string): void {
  window.location.hash = hashValue;
}

export default function App(): JSX.Element {
  const [surface, setSurface] = useState<SurfaceId>(() => resolveSurface(window.location.hash));

  useEffect(() => {
    const handleHashChange = (): void => {
      setSurface(resolveSurface(window.location.hash));
    };

    window.addEventListener("hashchange", handleHashChange);
    return () => {
      window.removeEventListener("hashchange", handleHashChange);
    };
  }, []);

  if (surface === "authoring") {
    return (
      <Suspense fallback={<div>Loading authoring surface...</div>}>
        <AuthoringApp onReturnToWorkbench={() => navigateTo("#/workbench")} />
      </Suspense>
    );
  }

  return (
    <Suspense fallback={<div>Loading workbench surface...</div>}>
      <ReactWorkbenchApp onOpenAuthoringStudio={() => navigateTo("#/authoring/home")} />
    </Suspense>
  );
}
