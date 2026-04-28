import React from "react";
import type { ValidationDiagnostic } from "../../types";
import {
  fetchRuntimeMetadataValidation,
  type RuntimeMetadataValidationResponse
} from "../services/authoringApi";

export type ServerValidationResult = {
  valid: boolean;
  diagnostics: ValidationDiagnostic[];
};

type ValidationFetcher = (
  modelJson: string,
  signal?: AbortSignal
) => Promise<RuntimeMetadataValidationResponse>;

type ServerValidationRunnerCallbacks = {
  onPendingChange: (pending: boolean) => void;
  onResultChange: (result: ServerValidationResult | null) => void;
};

const DEFAULT_DEBOUNCE_MS = 350;

function buildDiagnosticKey(diagnostic: ValidationDiagnostic): string {
  return [
    diagnostic.layer,
    diagnostic.severity,
    diagnostic.code,
    diagnostic.path ?? "",
    diagnostic.message,
    diagnostic.concept ?? "",
    diagnostic.field ?? "",
    diagnostic.ruleName ?? ""
  ].join("::");
}

export function mergeValidationDiagnostics(
  diagnostics: ValidationDiagnostic[],
  serverDiagnostics: ValidationDiagnostic[]
): ValidationDiagnostic[] {
  const merged = [...diagnostics, ...serverDiagnostics];
  const seen = new Set<string>();
  return merged.filter((diagnostic) => {
    const key = buildDiagnosticKey(diagnostic);
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

export function normalizeServerValidationResponse(
  response: RuntimeMetadataValidationResponse | null | undefined
): ServerValidationResult {
  return {
    valid: response?.valid !== false,
    diagnostics: Array.isArray(response?.diagnostics) ? response!.diagnostics : []
  };
}

export async function requestServerValidation(
  modelJson: string,
  fetchValidation: ValidationFetcher,
  signal?: AbortSignal
): Promise<ServerValidationResult | null> {
  try {
    const response = await fetchValidation(modelJson, signal);
    return normalizeServerValidationResponse(response);
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      return null;
    }
    return null;
  }
}

export function createServerValidationRunner(
  fetchValidation: ValidationFetcher,
  callbacks: ServerValidationRunnerCallbacks,
  debounceMs = DEFAULT_DEBOUNCE_MS
): {
  validate: (modelJson: string | null) => void;
  flush: (modelJson: string | null) => Promise<ServerValidationResult | null>;
  dispose: () => void;
} {
  let timerId: ReturnType<typeof setTimeout> | null = null;
  let abortController: AbortController | null = null;
  let requestVersion = 0;

  const cancelScheduledWork = (): void => {
    if (timerId != null) {
      clearTimeout(timerId);
      timerId = null;
    }
    abortController?.abort();
    abortController = null;
  };

  const runValidation = async (
    modelJson: string,
    currentVersion: number
  ): Promise<ServerValidationResult | null> => {
    abortController = new AbortController();
    const controller = abortController;
    const result = await requestServerValidation(modelJson, fetchValidation, controller.signal);
    if (controller.signal.aborted || currentVersion != requestVersion) {
      return result;
    }
    callbacks.onResultChange(result);
    callbacks.onPendingChange(false);
    abortController = null;
    return result;
  };

  const reset = (): void => {
    cancelScheduledWork();
    callbacks.onResultChange(null);
    callbacks.onPendingChange(false);
  };

  return {
    validate(modelJson) {
      requestVersion += 1;
      cancelScheduledWork();
      if (!modelJson) {
        callbacks.onResultChange(null);
        callbacks.onPendingChange(false);
        return;
      }

      callbacks.onPendingChange(true);
      const currentVersion = requestVersion;
      timerId = setTimeout(() => {
        timerId = null;
        void runValidation(modelJson, currentVersion);
      }, debounceMs);
    },
    async flush(modelJson) {
      requestVersion += 1;
      cancelScheduledWork();
      if (!modelJson) {
        callbacks.onResultChange(null);
        callbacks.onPendingChange(false);
        return null;
      }

      callbacks.onPendingChange(true);
      return runValidation(modelJson, requestVersion);
    },
    dispose() {
      requestVersion += 1;
      reset();
    }
  };
}

export function useServerValidation(
  modelJson: string | null,
  options?: {
    debounceMs?: number;
    fetchValidation?: ValidationFetcher;
  }
): {
  result: ServerValidationResult | null;
  pending: boolean;
  validate: (nextModelJson?: string | null) => Promise<ServerValidationResult | null>;
} {
  const debounceMs = options?.debounceMs ?? DEFAULT_DEBOUNCE_MS;
  const fetchValidation = options?.fetchValidation ?? fetchRuntimeMetadataValidation;
  const [result, setResult] = React.useState<ServerValidationResult | null>(null);
  const [pending, setPending] = React.useState(false);
  const runnerRef = React.useRef<ReturnType<typeof createServerValidationRunner> | null>(null);

  React.useEffect(() => {
    const runner = createServerValidationRunner(fetchValidation, {
      onPendingChange: setPending,
      onResultChange: setResult
    }, debounceMs);
    runnerRef.current = runner;
    return () => {
      runner.dispose();
      if (runnerRef.current === runner) {
        runnerRef.current = null;
      }
    };
  }, [debounceMs, fetchValidation]);

  React.useEffect(() => {
    runnerRef.current?.validate(modelJson);
  }, [modelJson]);

  const validate = async (nextModelJson?: string | null): Promise<ServerValidationResult | null> =>
    runnerRef.current?.flush(nextModelJson ?? modelJson) ?? null;

  return {
    result,
    pending,
    validate
  };
}
