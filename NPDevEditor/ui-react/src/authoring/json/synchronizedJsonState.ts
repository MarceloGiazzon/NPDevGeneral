import type { JsonValidationIssue } from "./jsonEditorTypes";
import { parseJsonDocument, serializeJsonDocument } from "../services/serialization/jsonSerialization";

export type SynchronizedJsonSnapshot = {
  draftText: string;
  lastAppliedDraftText: string;
  lastAppliedCanonicalText: string;
  issues: JsonValidationIssue[];
  hasExternalConflict: boolean;
};

export type SynchronizedJsonDraftResult<T> = {
  snapshot: SynchronizedJsonSnapshot;
  appliedDocument?: T;
};

export function createSynchronizedJsonSnapshot<T>(
  document: T,
  validateDocument: (document: T) => JsonValidationIssue[]
): SynchronizedJsonSnapshot {
  const canonicalText = serializeJsonDocument(document);
  return {
    draftText: canonicalText,
    lastAppliedDraftText: canonicalText,
    lastAppliedCanonicalText: canonicalText,
    issues: validateDocument(document),
    hasExternalConflict: false
  };
}

export function applySynchronizedJsonDraft<T>(
  snapshot: SynchronizedJsonSnapshot,
  draftText: string,
  validateDocument: (document: T) => JsonValidationIssue[]
): SynchronizedJsonDraftResult<T> {
  const parsed = parseJsonDocument<T>(draftText);
  if (!parsed.ok) {
    return {
      snapshot: {
        ...snapshot,
        draftText,
        issues: [parsed.issue]
      }
    };
  }

  const issues = validateDocument(parsed.value);
  const canonicalText = serializeJsonDocument(parsed.value);
  return {
    snapshot: {
      draftText,
      lastAppliedDraftText: draftText,
      lastAppliedCanonicalText: canonicalText,
      issues,
      hasExternalConflict: false
    },
    appliedDocument: parsed.value
  };
}

export function reconcileSynchronizedJsonSnapshot<T>(
  snapshot: SynchronizedJsonSnapshot,
  document: T,
  validateDocument: (document: T) => JsonValidationIssue[]
): SynchronizedJsonSnapshot {
  const canonicalText = serializeJsonDocument(document);

  if (snapshot.draftText === snapshot.lastAppliedDraftText) {
    return {
      draftText: canonicalText,
      lastAppliedDraftText: canonicalText,
      lastAppliedCanonicalText: canonicalText,
      issues: validateDocument(document),
      hasExternalConflict: false
    };
  }

  if (canonicalText !== snapshot.lastAppliedCanonicalText) {
    return {
      ...snapshot,
      hasExternalConflict: true
    };
  }

  return snapshot;
}
