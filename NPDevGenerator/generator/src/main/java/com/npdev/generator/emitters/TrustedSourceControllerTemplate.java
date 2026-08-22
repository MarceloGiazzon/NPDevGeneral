package com.npdev.generator.emitters;

import com.npdev.generator.emitters.trustedsource.model.TrustedFlow;
import com.npdev.generator.emitters.trustedsource.model.TrustedPanel;
import com.npdev.generator.emitters.trustedsource.model.TrustedProcedure;
import com.npdev.generator.emitters.trustedsource.model.TrustedWidget;

import java.util.List;

import static com.npdev.generator.emitters.TrustedSourceTemplateSupport.quote;

/**
 * Static Java source template for {@code GeneratedTrustedSourceRuntimeController}: the single
 * REST controller a generated app mounts for the whole trusted-source runtime surface -- action/
 * flow invocation and evidence endpoints (fixed), plus one route per declared panel/widget.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2) into its own file purely because of size.
 */
final class TrustedSourceControllerTemplate {
    private static final String FULL_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; form-action 'self'; object-src 'none'; base-uri 'self'; frame-src 'none'; frame-ancestors 'none'; worker-src 'none'; manifest-src 'self'; upgrade-insecure-requests";

    private TrustedSourceControllerTemplate() {
    }

    static String controllerSource(
            List<TrustedProcedure> procedures,
            List<TrustedPanel> panels,
            List<TrustedWidget> widgets,
            List<TrustedFlow> flows
    ) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.generated.runtime.service.RuntimeContextService;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
                import jakarta.servlet.http.HttpServletRequest;
                import org.springframework.core.io.ClassPathResource;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.MediaType;
                import org.springframework.http.ResponseEntity;
                import org.springframework.util.StreamUtils;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                import java.nio.charset.StandardCharsets;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                @RestController
                public class GeneratedTrustedSourceRuntimeController {
                    public static final String FULL_CSP = """).append(quote(FULL_CSP)).append("""
                ;
                    private final RuntimeContextService runtimeContextService;
                    private final ConceptGateway conceptGateway;
                    private final GeneratedActionKernelRunner actionKernelRunner;
                    private final GeneratedFlowCodaRunner flowCodaRunner;
                    private final KernelFacade kernelFacade;

                    public GeneratedTrustedSourceRuntimeController(
                            RuntimeContextService runtimeContextService,
                            ConceptGateway conceptGateway,
                            GeneratedActionKernelRunner actionKernelRunner,
                            GeneratedFlowCodaRunner flowCodaRunner,
                            KernelFacade kernelFacade
                    ) {
                        this.runtimeContextService = runtimeContextService;
                        this.conceptGateway = conceptGateway;
                        this.actionKernelRunner = actionKernelRunner;
                        this.flowCodaRunner = flowCodaRunner;
                        this.kernelFacade = kernelFacade;
                    }

                    @PostMapping(value = "/generated/actions/{actionName}/run", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> runAction(
                            @PathVariable String actionName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedActionExecutionResponse response = actionKernelRunner.run(
                                actionName,
                                GeneratedActionExecutionRequest.from(body),
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @PostMapping(value = "/generated/procedures/{procedureName}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> invokeProcedure(
                            @PathVariable String procedureName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedActionExecutionResponse response = actionKernelRunner.run(
                                procedureName,
                                GeneratedActionExecutionRequest.from(body),
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @PostMapping(value = "/generated/flows/{flowName}/start", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> startFlow(
                            @PathVariable String flowName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedFlowExecutionResponse response = flowCodaRunner.start(
                                flowName,
                                GeneratedFlowExecutionRequest.from(body),
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @PostMapping(value = "/generated/flows/{flowName}/events/{eventName}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> publishFlowEvent(
                            @PathVariable String flowName,
                            @PathVariable String eventName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedFlowExecutionResponse response = flowCodaRunner.publishEventAndResume(
                                flowName,
                                eventName,
                                body == null ? Map.of() : body,
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @PostMapping(value = "/generated/flows/{flowName}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> resumeFlowWithEvent(
                            @PathVariable String flowName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        Map<String, Object> input = body == null ? Map.of() : body;
                        GeneratedFlowExecutionResponse response = flowCodaRunner.publishEventAndResume(
                                flowName,
                                stringValue(input.get("eventName")),
                                input,
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @GetMapping(value = "/generated/actions/executions/{executionId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> actionExecutionEvidence(
                            @PathVariable String executionId,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        return ResponseEntity.ok(kernelFacade.generatedActionEvidenceByExecution(executionId, context));
                    }

                    @GetMapping(value = "/generated/actions/correlations/{correlationId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> actionCorrelationEvidence(
                            @PathVariable String correlationId,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        return ResponseEntity.ok(kernelFacade.generatedActionEvidenceByCorrelation(correlationId, context));
                    }
                    // Item 18 Flow Evidence Viewer Extension start
                    @GetMapping(value = "/generated/flows/executions/{executionId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> flowExecutionEvidence(
                            @PathVariable String executionId,
                            HttpServletRequest request
                    ) {
                        Map<String, Object> evidence = actionExecutionEvidence(executionId, request).getBody();
                        Map<String, Object> flowEvidence = new java.util.LinkedHashMap<>();
                        flowEvidence.put("viewerType", "flow-execution");
                        flowEvidence.put("evidenceStatus", evidence == null ? "unavailable" : "available");
                        flowEvidence.put("executionId", executionId);
                        flowEvidence.put("flowInstanceId", evidence == null ? null : evidence.getOrDefault("flowInstanceId", executionId));
                        flowEvidence.put("flowStatus", evidence == null ? "unavailable: not returned by runtime" : evidence.getOrDefault("flowStatus", evidence.getOrDefault("flowInstanceStatus", evidence.getOrDefault("status", "unavailable: not returned by runtime"))));
                        flowEvidence.put("sourceEvidenceEndpoint", "/generated/actions/executions/" + executionId);
                        flowEvidence.put("sourceEvidenceStatus", evidence == null ? "unavailable: delegated action evidence returned null" : "available");
                        flowEvidence.put("evidence", evidence == null ? java.util.Map.of() : evidence);
                        flowEvidence.put("truth", "flow evidence viewer alias over accepted generated action/evidence surface");
                        return ResponseEntity.ok(flowEvidence);
                    }

                    @GetMapping(value = "/generated/flows/instances/{flowInstanceId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> flowInstanceEvidence(
                            @PathVariable String flowInstanceId,
                            HttpServletRequest request
                    ) {
                        Map<String, Object> evidence = actionExecutionEvidence(flowInstanceId, request).getBody();
                        Map<String, Object> flowEvidence = new java.util.LinkedHashMap<>();
                        flowEvidence.put("viewerType", "flow-instance");
                        flowEvidence.put("evidenceStatus", evidence == null ? "unavailable" : "available");
                        flowEvidence.put("flowInstanceId", flowInstanceId);
                        flowEvidence.put("executionId", evidence == null ? flowInstanceId : evidence.getOrDefault("executionId", flowInstanceId));
                        flowEvidence.put("flowStatus", evidence == null ? "unavailable: not returned by runtime" : evidence.getOrDefault("flowStatus", evidence.getOrDefault("flowInstanceStatus", evidence.getOrDefault("status", "unavailable: not returned by runtime"))));
                        flowEvidence.put("sourceEvidenceEndpoint", "/generated/actions/executions/" + flowInstanceId);
                        flowEvidence.put("sourceEvidenceStatus", evidence == null ? "unavailable: delegated action evidence returned null" : "available");
                        flowEvidence.put("evidence", evidence == null ? java.util.Map.of() : evidence);
                        flowEvidence.put("truth", "flow instance evidence viewer alias over accepted generated action/evidence surface");
                        return ResponseEntity.ok(flowEvidence);
                    }

                    @GetMapping(value = "/generated/flows/correlations/{correlationId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> flowCorrelationEvidence(
                            @PathVariable String correlationId,
                            HttpServletRequest request
                    ) {
                        Map<String, Object> evidence = actionCorrelationEvidence(correlationId, request).getBody();
                        Map<String, Object> flowEvidence = new java.util.LinkedHashMap<>();
                        flowEvidence.put("viewerType", "flow-correlation");
                        flowEvidence.put("evidenceStatus", evidence == null ? "unavailable" : "available");
                        flowEvidence.put("correlationId", correlationId);
                        flowEvidence.put("sourceEvidenceEndpoint", "/generated/actions/correlations/" + correlationId);
                        flowEvidence.put("sourceEvidenceStatus", evidence == null ? "unavailable: delegated correlation evidence returned null" : "available");
                        flowEvidence.put("evidence", evidence == null ? java.util.Map.of() : evidence);
                        flowEvidence.put("truth", "flow correlation evidence viewer alias over accepted generated action/correlation evidence surface");
                        return ResponseEntity.ok(flowEvidence);
                    }
                    // Item 18 Flow Evidence Viewer Extension end
                """);

        for (TrustedPanel panel : panels) {
            source.append("    @GetMapping(value = ").append(quote(panel.route())).append(", produces = MediaType.TEXT_HTML_VALUE)\n")
                    .append("    public ResponseEntity<String> panel").append(methodSuffix(panel.id())).append("(HttpServletRequest request) throws Exception {\n")
                    .append("        ExecutionContext context = runtimeContextService.currentContext(request);\n")
                        .append("        int before = 0;\n")
                    .append("        ResponseEntity<Map<String, Object>> rejection = rejectIfUnauthorized(context, Map.of(), ")
                    .append(quote(panel.requiredRole())).append(", false, before);\n")
                    .append("        if (rejection != null) {\n")
                    .append("            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(\"\");\n")
                    .append("        }\n")
                    .append("        String html = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/panel/" + panel.resourceName()))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                                        .append("        String bridge = \"<script src=\\\"/generated/trusted-source/npdev-panel-runtime.js\\\"></script>\";\n")
.append("        if (html.contains(\"</head>\")) {\n")
                    .append("            html = html.replace(\"</head>\", bridge + \"</head>\");\n")
                    .append("        } else {\n")
                    .append("            html = bridge + html;\n")
                    .append("        }\n")
                    .append("        return ResponseEntity.ok().header(\"Content-Security-Policy\", FULL_CSP).contentType(MediaType.TEXT_HTML).body(html);\n")
                    .append("    }\n\n");
            source.append("    @GetMapping(value = ").append(quote("/generated/trusted-source/panel/" + panel.cssResourceName())).append(", produces = \"text/css\")\n")
                    .append("    public ResponseEntity<String> panelCss").append(methodSuffix(panel.id())).append("() throws Exception {\n")
                    .append("        String css = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/panel/" + panel.cssResourceName()))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                    .append("        return ResponseEntity.ok().contentType(MediaType.valueOf(\"text/css\")).body(css);\n")
                    .append("    }\n\n");
            source.append("    @GetMapping(value = ").append(quote("/generated/trusted-source/panel/" + panel.jsResourceName())).append(", produces = \"application/javascript\")\n")
                    .append("    public ResponseEntity<String> panelJs").append(methodSuffix(panel.id())).append("() throws Exception {\n")
                    .append("        String js = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/panel/" + panel.jsResourceName()))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                    .append("        return ResponseEntity.ok().contentType(MediaType.valueOf(\"application/javascript\")).body(js);\n")
                    .append("    }\n\n");
        }

        for (TrustedWidget widget : widgets) {
            String urlPath = widget.relativePath().replace('\\', '/');
            source.append("    @GetMapping(value = ").append(quote("/generated/trusted-source/widget/" + urlPath)).append(", produces = \"application/javascript\")\n")
                    .append("    public ResponseEntity<String> widget").append(methodSuffix(widget.relativePath())).append("() throws Exception {\n")
                    .append("        String js = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/widget/" + urlPath))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                    .append("        return ResponseEntity.ok().contentType(MediaType.valueOf(\"application/javascript\")).body(js);\n")
                    .append("    }\n\n");
        }

        source.append("""
                    @GetMapping(value = "/generated/trusted-source/npdev-panel-runtime.js", produces = "application/javascript")
                    public ResponseEntity<String> panelRuntimeBridge() {
                        String js = \"""
                window.NPDev = window.NPDev || {};
                const NPDEV_ACTION_FIELDS = [
                  ['status', 'Status', 'data-npdev-status'],
                  ['executionId', 'Execution ID', 'data-npdev-execution-id'],
                  ['correlationId', 'Correlation ID', 'data-npdev-correlation-id'],
                  ['actionName', 'Action', 'data-npdev-action-name'],
                  ['procedureName', 'Procedure', 'data-npdev-procedure-name'],
                  ['capabilityId', 'Capability', 'data-npdev-capability-id'],
                  ['capabilityDispatchStatus', 'Dispatch', 'data-npdev-dispatch-status'],
                  ['eventStatus', 'Event', 'data-npdev-event-status'],
                  ['traceStatus', 'Trace', 'data-npdev-trace-status'],
                  ['auditStatus', 'Audit', 'data-npdev-audit-status'],
                  ['idempotencyStatus', 'Idempotency', 'data-npdev-idempotency-status'],
                  ['correlationStatus', 'Correlation', 'data-npdev-correlation-status'],
                  ['createdCount', 'Created count', 'data-npdev-created-count'],
                  ['sideEffectCountBefore', 'Side effects before', 'data-npdev-side-effect-before'],
                  ['sideEffectCountAfter', 'Side effects after', 'data-npdev-side-effect-after'],
                  ['message', 'Message', 'data-npdev-message'],
                  ['error', 'Error', 'data-npdev-error']
                ];
                function npdevEscape(value) {
                  return String(value).replace(/[&<>"']/g, function(ch) {
                    return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch];
                  });
                }
                function npdevFieldValue(response, key) {
                  if (!response || typeof response !== 'object' || !Object.prototype.hasOwnProperty.call(response, key)) {
                    return 'unavailable: not returned by runtime';
                  }
                  if (response[key] === null || response[key] === undefined) {
                    return 'unavailable: runtime returned null';
                  }
                  return String(response[key]);
                }
                function npdevRawField(response, key) {
                  if (!response || typeof response !== 'object' || !Object.prototype.hasOwnProperty.call(response, key)) {
                    return '';
                  }
                  const value = response[key];
                  if (value === null || value === undefined) {
                    return '';
                  }
                  return String(value).trim();
                }
                function npdevEvidenceLinksHtml(response) {
                  const executionId = npdevRawField(response, 'executionId');
                  const correlationId = npdevRawField(response, 'correlationId');
                  const links = [];
                  if (executionId) {
                    links.push('<a data-npdev-execution-evidence-link href="/generated/actions/executions/'
                        + encodeURIComponent(executionId)
                        + '">View execution evidence</a>');
                  }
                  if (correlationId) {
                    links.push('<a data-npdev-correlation-evidence-link href="/generated/actions/correlations/'
                        + encodeURIComponent(correlationId)
                        + '">View correlation evidence</a>');
                  }
                  if (links.length === 0) {
                    return '<div class="npdev-action-result__evidence" data-npdev-evidence-link-status>'
                        + 'Evidence link unavailable: executionId/correlationId not returned by runtime'
                        + '</div>';
                  }
                  return '<div class="npdev-action-result__evidence" data-npdev-evidence-link-status>Evidence links available: '
                      + links.join(' ')
                      + '</div>';
                }
                function npdevResultState(response) {
                  const status = npdevFieldValue(response, 'status').toLowerCase();
                  const idempotency = npdevFieldValue(response, 'idempotencyStatus').toLowerCase();
                  const error = npdevFieldValue(response, 'error');
                  if (status.includes('fail') || status.includes('error') || status.includes('reject') || (error && !error.startsWith('unavailable:') && error.trim() !== '')) {
                    return 'error';
                  }
                  if (idempotency.includes('reused') || idempotency.includes('prevented')) {
                    return 'reused';
                  }
                  return 'success';
                }
                window.NPDev.renderActionResultHtml = function(response) {
                  const state = npdevResultState(response);
                  const title = state === 'error'
                    ? 'Action failed'
                    : state === 'reused'
                        ? 'Action reused / duplicate prevented'
                        : 'Action completed';
                  const rows = NPDEV_ACTION_FIELDS.map(function(field) {
                    const value = npdevFieldValue(response, field[0]);
                    return '<div class="npdev-action-result__row" ' + field[2] + '><span class="npdev-action-result__label">'
                        + npdevEscape(field[1])
                        + '</span><span class="npdev-action-result__value">'
                        + npdevEscape(value)
                        + '</span></div>';
                  }).join('');
                  return '<section class="npdev-action-result npdev-action-result--' + state + '" data-npdev-action-result aria-live="polite">'
                      + '<h2>' + npdevEscape(title) + '</h2>'
                      + rows
                      + npdevEvidenceLinksHtml(response)
                      + '</section>';
                };
                function npdevDefaultResultContainer() {
                  let container = document.querySelector('[data-npdev-action-result-root]');
                  if (!container) {
                    container = document.createElement('div');
                    container.setAttribute('data-npdev-action-result-root', '');
                    document.body.appendChild(container);
                  }
                  return container;
                }
                window.NPDev.renderActionResult = function(container, response) {
                  const target = container || npdevDefaultResultContainer();
                  target.innerHTML = window.NPDev.renderActionResultHtml(response || {});
                  return target;
                };
                async function npdevParseResponse(response) {
                  const contentType = response.headers.get('content-type') || '';
                  if (contentType.includes('application/json')) {
                    return await response.json();
                  }
                  const text = await response.text();
                  return { status: response.ok ? 'ok' : 'error', error: text || ('HTTP ' + response.status) };
                }
                // Item 17 Flow UI Visibility bridge start
                function npdevFlowUiEscape(value) {
                  if (value === undefined) {
                    return 'unavailable: not returned by runtime';
                  }
                  if (value === null) {
                    return 'unavailable: runtime returned null';
                  }
                  return String(value)
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;')
                    .replace(/'/g, '&#39;');
                }
                function npdevFlowUiValue(response, key) {
                  if (!response || !Object.prototype.hasOwnProperty.call(response, key)) {
                    return 'unavailable: not returned by runtime';
                  }
                  if (response[key] === null) {
                    return 'unavailable: runtime returned null';
                  }
                  return response[key];
                }
                function npdevFlowUiFirstValue(response, keys) {
                  for (const key of keys) {
                    const value = npdevFlowUiValue(response, key);
                    if (String(value).indexOf('unavailable:') !== 0) {
                      return value;
                    }
                  }
                  return npdevFlowUiValue(response, keys[0]);
                }
                function npdevAuthHeaders(base) {
                  // Mirrors shell.js's TOKEN_STORAGE_KEYS/authHeaders: this script has no reliable
                  // way to know whether the app is running in jwt or apiKey mode, so it stores/reads
                  // the same credential under the same shared keys and sends it as BOTH headers --
                  // whichever auth filter is actually active picks up the one it understands.
                  const headers = base || {};
                  const token = window.NPDevApiKey
                    || (window.localStorage && (
                      window.localStorage.getItem('npdev.shell.token')
                      || window.localStorage.getItem('npdev.businessUi.apiKey')
                      || window.localStorage.getItem('npdev.apiKey')
                    ))
                    || '';
                  if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                    headers['X-Api-Key'] = token;
                  }
                  return headers;
                }
                window.NPDev.authHeaders = npdevAuthHeaders;
                function npdevFlowUiHeaders() {
                  return npdevAuthHeaders({ 'Content-Type': 'application/json' });
                }
                async function npdevFlowUiPost(url, payload) {
                  const response = await fetch(url, {
                    method: 'POST',
                    headers: npdevFlowUiHeaders(),
                    body: JSON.stringify(payload || {})
                  });
                  let body = {};
                  try {
                    body = await response.json();
                  } catch (error) {
                    body = {
                      status: 'failed',
                      error: 'unavailable: runtime response was not JSON',
                      reason: String(error && error.message ? error.message : error)
                    };
                  }
                  if (!response.ok) {
                    body.httpStatus = response.status;
                    body.status = body.status || 'failed';
                    body.error = body.error || body.reason || 'flow request failed';
                  }
                  return body;
                }
                function npdevFlowUiEvidenceId(response) {
                  return npdevFlowUiValue(response, 'flowInstanceId') !== 'unavailable: not returned by runtime'
                    ? npdevFlowUiValue(response, 'flowInstanceId')
                    : npdevFlowUiValue(response, 'executionId');
                }
                function npdevFlowUiRow(attributeName, label, value) {
                  return '<div class="npdev-flow-result-row" ' + attributeName + '="' + npdevFlowUiEscape(value) + '">' +
                    '<strong>' + npdevFlowUiEscape(label) + ':</strong> ' + npdevFlowUiEscape(value) +
                    '</div>';
                }
                function npdevFlowUiLinks(response) {
                  const executionId = npdevFlowUiEvidenceId(response);
                  const correlationId = npdevFlowUiValue(response, 'correlationId');
                  let html = '';
                  if (executionId && String(executionId).indexOf('unavailable:') !== 0) {
                    html += '<a data-npdev-flow-evidence-link href="/generated/flows/executions/' +
                      encodeURIComponent(executionId) + '">View flow/execution evidence</a>';
                  }
                  if (correlationId && String(correlationId).indexOf('unavailable:') !== 0) {
                    html += '<a data-npdev-flow-correlation-evidence-link href="/generated/flows/correlations/' +
                      encodeURIComponent(correlationId) + '">View correlation evidence</a>';
                  }
                  if (!html) {
                    html = '<span data-npdev-flow-evidence-link-status>' +
                      'Evidence link unavailable: executionId/flowInstanceId/correlationId not returned by runtime' +
                      '</span>';
                  }
                  return '<div class="npdev-flow-result-links">' + html + '</div>';
                }
                function npdevFlowUiAutoRender(response) {
                  if (typeof document === 'undefined' || !document.body || !window.NPDev.renderFlowResult) {
                    return;
                  }
                  let container = document.querySelector('[data-npdev-flow-result-target]');
                  if (!container) {
                    container = document.createElement('section');
                    container.setAttribute('data-npdev-flow-result-auto', 'true');
                    document.body.appendChild(container);
                  }
                  window.NPDev.renderFlowResult(container, response);
                }
                window.NPDev.renderFlowResultHtml = function(response) {
                  response = response || {};
                  const flowStatus = npdevFlowUiFirstValue(response, ['flowStatus', 'flowInstanceStatus', 'status']);
                  const statusClass = String(flowStatus).toLowerCase().replace(/[^a-z0-9_-]+/g, '-');
                  return '<section class="npdev-flow-result npdev-flow-result-' + npdevFlowUiEscape(statusClass) + '" data-npdev-flow-result>' +
                    '<h3>Flow execution result</h3>' +
                    npdevFlowUiRow('data-npdev-flow-name', 'Flow name', npdevFlowUiValue(response, 'flowName')) +
                    npdevFlowUiRow('data-npdev-flow-instance-id', 'Flow instance ID', npdevFlowUiFirstValue(response, ['flowInstanceId', 'executionId'])) +
                    npdevFlowUiRow('data-npdev-flow-status', 'Flow status', flowStatus) +
                    npdevFlowUiRow('data-npdev-execution-id', 'Execution ID', npdevFlowUiValue(response, 'executionId')) +
                    npdevFlowUiRow('data-npdev-correlation-id', 'Correlation ID', npdevFlowUiValue(response, 'correlationId')) +
                    npdevFlowUiRow('data-npdev-waiting-status', 'Waiting status', npdevFlowUiValue(response, 'waitingStatus')) +
                    npdevFlowUiRow('data-npdev-resume-status', 'Resume status', npdevFlowUiValue(response, 'resumeStatus')) +
                    npdevFlowUiRow('data-npdev-capability-id', 'Capability ID', npdevFlowUiValue(response, 'capabilityId')) +
                    npdevFlowUiRow('data-npdev-dispatch-status', 'Capability dispatch status', npdevFlowUiValue(response, 'capabilityDispatchStatus')) +
                    npdevFlowUiRow('data-npdev-event-status', 'Event status', npdevFlowUiValue(response, 'eventStatus')) +
                    npdevFlowUiRow('data-npdev-trace-status', 'Trace status', npdevFlowUiValue(response, 'traceStatus')) +
                    npdevFlowUiRow('data-npdev-audit-status', 'Audit status', npdevFlowUiValue(response, 'auditStatus')) +
                    npdevFlowUiRow('data-npdev-idempotency-status', 'Idempotency status', npdevFlowUiValue(response, 'idempotencyStatus')) +
                    npdevFlowUiRow('data-npdev-correlation-status', 'Correlation status', npdevFlowUiValue(response, 'correlationStatus')) +
                    npdevFlowUiRow('data-npdev-created-count', 'Created count', npdevFlowUiValue(response, 'createdCount')) +
                    npdevFlowUiRow('data-npdev-side-effect-before', 'Side effect before', npdevFlowUiValue(response, 'sideEffectCountBefore')) +
                    npdevFlowUiRow('data-npdev-side-effect-after', 'Side effect after', npdevFlowUiValue(response, 'sideEffectCountAfter')) +
                    npdevFlowUiRow('data-npdev-flow-message', 'Message', npdevFlowUiValue(response, 'message')) +
                    npdevFlowUiRow('data-npdev-flow-error', 'Error', npdevFlowUiValue(response, 'error')) +
                    npdevFlowUiLinks(response) +
                    '</section>';
                };
                window.NPDev.renderFlowResult = function(container, response) {
                  const target = typeof container === 'string' ? document.querySelector(container) : container;
                  if (!target) {
                    throw new Error('Flow result container not found');
                  }
                  target.innerHTML = window.NPDev.renderFlowResultHtml(response || {});
                  return target;
                };
                window.NPDev.startFlow = async function(flowName, payload) {
                  const body = await npdevFlowUiPost('/generated/flows/' + encodeURIComponent(flowName) + '/start', payload || {});
                  npdevFlowUiAutoRender(body);
                  return body;
                };
                window.NPDev.resumeFlow = async function(flowName, eventName, payload) {
                  const body = await npdevFlowUiPost('/generated/flows/' + encodeURIComponent(flowName) + '/events/' + encodeURIComponent(eventName), payload || {});
                  npdevFlowUiAutoRender(body);
                  return body;
                };
                // Item 17 Flow UI Visibility bridge end
                window.NPDev.callProcedure = async function(name, payload) {
                  const headers = npdevAuthHeaders({ 'Content-Type': 'application/json' });
                  const response = await fetch('/generated/procedures/' + encodeURIComponent(name), {
                    method: 'POST',
                    headers,
                    body: JSON.stringify(payload || {})
                  });
                  const body = await npdevParseResponse(response);
                  window.NPDev.renderActionResult(null, body);
                  if (!response.ok) {
                    const error = new Error(body.message || body.reason || body.error || 'trusted procedure failed');
                    error.responseBody = body;
                    error.httpStatus = response.status;
                    throw error;
                  }
                  return body;
                };
                \""";
                        return ResponseEntity.ok().contentType(MediaType.valueOf("application/javascript")).body(js);
                    }

                    @GetMapping(value = "/generated/trusted-source/state/{conceptName}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> trustedSourceState(
                            @PathVariable String conceptName,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        ResponseEntity<Map<String, Object>> rejection = rejectIfUnauthorized(context, Map.of(), "ADMIN", false, 0);
                        if (rejection != null) {
                            return rejection;
                        }
                        List<ConceptRecord> records = conceptGateway.list(
                                new ConceptListRequest(conceptName, context.tenantId()),
                                context
                        );
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (ConceptRecord record : records) {
                            Map<String, Object> row = new LinkedHashMap<>(record.data());
                            row.put("id", record.id());
                            row.put("tenantId", record.tenantId());
                            rows.add(row);
                        }
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "ok");
                        response.put("conceptName", conceptName);
                        response.put("tenantId", context.tenantId());
                        response.put("count", rows.size());
                        response.put("records", rows);
                        return ResponseEntity.ok(response);
                    }

                    private ResponseEntity<Map<String, Object>> rejectIfUnauthorized(
                            ExecutionContext context,
                            Map<String, Object> input,
                            String requiredRole,
                            boolean tenantScoped,
                            int before
                    ) {
                        if (!context.hasRole(requiredRole)) {
                            return rejected("missing-role", before);
                        }
                        if (tenantScoped) {
                            String requestedTenant = stringValue(input.get("tenantId"));
                            if (!requestedTenant.isBlank() && !requestedTenant.equals(context.tenantId())) {
                                return rejected("wrong-tenant", before);
                            }
                        }
                        return null;
                    }

                    private ResponseEntity<Map<String, Object>> rejected(String reason, int before) {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "rejected");
                        response.put("reason", reason);
                        response.put("sideEffectCountBefore", before);
                        response.put("sideEffectCountAfter", before);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                    }

                    private int runtimeCount(ExecutionContext context, String conceptName) {
                        return conceptGateway.list(new ConceptListRequest(conceptName, context.tenantId()), context).size();
                    }

                    private static HttpStatus httpStatusFor(GeneratedActionExecutionResponse response) {
                        if (response == null) {
                            return HttpStatus.INTERNAL_SERVER_ERROR;
                        }
                        if ("ok".equalsIgnoreCase(response.status())) {
                            return HttpStatus.OK;
                        }
                        if ("rejected".equalsIgnoreCase(response.status())) {
                            if ("unknown-action".equalsIgnoreCase(response.error())) {
                                return HttpStatus.NOT_FOUND;
                            }
                            return HttpStatus.FORBIDDEN;
                        }
                        return HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    private static HttpStatus httpStatusFor(GeneratedFlowExecutionResponse response) {
                        if (response == null) {
                            return HttpStatus.INTERNAL_SERVER_ERROR;
                        }
                        if ("ok".equalsIgnoreCase(response.status())) {
                            return HttpStatus.OK;
                        }
                        if ("waiting".equalsIgnoreCase(response.status())
                                || "WAITING_EVENT".equalsIgnoreCase(response.flowStatus())) {
                            return HttpStatus.OK;
                        }
                        if ("rejected".equalsIgnoreCase(response.status())) {
                            if ("unknown-flow".equalsIgnoreCase(response.error())) {
                                return HttpStatus.NOT_FOUND;
                            }
                            return HttpStatus.FORBIDDEN;
                        }
                        return HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }
                }
                """);
        return source.toString();
    }

    private static String methodSuffix(String value) {
        StringBuilder out = new StringBuilder();
        boolean capitalizeNext = true;
        for (char ch : value.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                out.append(capitalizeNext ? Character.toUpperCase(ch) : ch);
                capitalizeNext = false;
            } else {
                capitalizeNext = true;
            }
        }
        return out.isEmpty() ? "TrustedSource" : out.toString();
    }
}
