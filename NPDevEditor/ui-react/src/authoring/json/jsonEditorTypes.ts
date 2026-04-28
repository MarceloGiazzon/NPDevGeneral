export type JsonValidationIssue = {
  severity: "error" | "warning";
  path: string;
  message: string;
};

export type JsonEditorMode = "form" | "json";

export type JsonParseResult<T> =
  | {
      ok: true;
      value: T;
    }
  | {
      ok: false;
      issue: JsonValidationIssue;
    };
