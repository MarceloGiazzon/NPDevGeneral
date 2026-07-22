# Deferred Custom Scenario Assets

CP3 uses the locked custom Path B decision for active scenarios whose `app.kind` is a custom-only kind. The active negative scenarios now reject those kinds at `ai-model-schema` with `FAIL_KIND_UNSUPPORTED` and `AI_MODEL_KIND_UNSUPPORTED`.

The files under this directory are preserved orphan custom panel/procedure assets from those negative scenarios. They are not active golden scenario inputs and are excluded from active scenario validation.
