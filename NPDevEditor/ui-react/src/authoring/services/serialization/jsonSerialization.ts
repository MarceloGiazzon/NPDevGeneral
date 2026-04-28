import type { JsonParseResult } from "../../authoring/json/jsonEditorTypes";

function issueFromParseError(error: unknown, sourceText: string): { path: string; message: string } {
  if (!(error instanceof Error)) {
    return {
      path: "$",
      message: "Unknown JSON parsing error."
    };
  }

  const match = error.message.match(/position\s+(\d+)/i);
  if (!match) {
    return {
      path: "$",
      message: error.message
    };
  }

  const position = Number(match[1]);
  const normalizedPosition = Number.isFinite(position) ? Math.max(position, 0) : 0;
  const prefix = sourceText.slice(0, normalizedPosition);
  const lines = prefix.split(/\r\n|\r|\n/);
  const line = lines.length;
  const column = (lines[lines.length - 1]?.length ?? 0) + 1;

  return {
    path: "$",
    message: `${error.message} (line ${line}, column ${column})`
  };
}

export function serializeJsonDocument<T>(document: T): string {
  return JSON.stringify(document, null, 2);
}

export function parseJsonDocument<T>(sourceText: string): JsonParseResult<T> {
  try {
    return {
      ok: true,
      value: JSON.parse(sourceText) as T
    };
  } catch (error) {
    const issue = issueFromParseError(error, sourceText);
    return {
      ok: false,
      issue: {
        severity: "error",
        path: issue.path,
        message: issue.message
      }
    };
  }
}
