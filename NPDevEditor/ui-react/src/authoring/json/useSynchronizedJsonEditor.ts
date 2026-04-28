import { useEffect, useMemo, useState } from "react";
import type { JsonEditorMode, JsonValidationIssue } from "./jsonEditorTypes";
import {
  applySynchronizedJsonDraft,
  createSynchronizedJsonSnapshot,
  reconcileSynchronizedJsonSnapshot,
  type SynchronizedJsonSnapshot
} from "./synchronizedJsonState";
import { serializeJsonDocument } from "../services/serialization/jsonSerialization";

type UseSynchronizedJsonEditorArgs<T> = {
  document: T;
  onApplyDocument: (document: T) => void;
  validateDocument: (document: T) => JsonValidationIssue[];
};

type SynchronizedJsonEditorState<T> = {
  mode: JsonEditorMode;
  setMode: (mode: JsonEditorMode) => void;
  draftText: string;
  issues: JsonValidationIssue[];
  hasPendingRawChanges: boolean;
  hasExternalConflict: boolean;
  onDraftChange: (value: string) => void;
  reloadFromForms: () => void;
};

export function useSynchronizedJsonEditor<T>({
  document,
  onApplyDocument,
  validateDocument
}: UseSynchronizedJsonEditorArgs<T>): SynchronizedJsonEditorState<T> {
  const serializedDocument = useMemo(() => serializeJsonDocument(document), [document]);
  const [mode, setMode] = useState<JsonEditorMode>("form");
  const [snapshot, setSnapshot] = useState<SynchronizedJsonSnapshot>(() =>
    createSynchronizedJsonSnapshot(document, validateDocument)
  );

  useEffect(() => {
    setSnapshot((current) => reconcileSynchronizedJsonSnapshot(current, document, validateDocument));
  }, [document, serializedDocument, validateDocument]);

  const onDraftChange = (value: string): void => {
    let appliedDocument: T | undefined;
    setSnapshot((current) => {
      const result = applySynchronizedJsonDraft(current, value, validateDocument);
      appliedDocument = result.appliedDocument;
      return result.snapshot;
    });
    if (appliedDocument) {
      onApplyDocument(appliedDocument);
    }
  };

  const reloadFromForms = (): void => {
    setSnapshot(createSynchronizedJsonSnapshot(document, validateDocument));
  };

  return {
    mode,
    setMode,
    draftText: snapshot.draftText,
    issues: snapshot.issues,
    hasPendingRawChanges: snapshot.draftText !== snapshot.lastAppliedDraftText,
    hasExternalConflict: snapshot.hasExternalConflict,
    onDraftChange,
    reloadFromForms
  };
}
