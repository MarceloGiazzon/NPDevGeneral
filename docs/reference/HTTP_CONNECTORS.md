# Declarative HTTP connectors (INT-1 / INT-2 / INT-3)

A model can call an outside REST API (a payment provider, a CRM, a shipping or tax service)
without writing Java. You declare the remote API as a capability with an `http` block, bind it to
adapter `http-connector`, and every operation becomes an ordinary capability operation that a flow
or procedure calls with a `capabilityCall` step. The platform performs the request, adds the
credentials from a named environment variable, enforces an egress policy and a deadline, and returns
the value at a JSON pointer in the response.

There is no new top-level array: the block lives on `capabilities[]` and on each
`capabilities[].operations[]` object, so a pack can ship a ready-made connector.

## Declaring a connector

```json
{
  "propertyScopes": [ { "name": "tenant" } ],
  "properties": [
    { "name": "paymentsBaseUrl", "type": "string", "default": "https://api.payments.example/v1",
      "settableAt": ["tenant"], "label": "Payments API base URL" }
  ],
  "capabilities": [
    {
      "name": "payments",
      "type": "HttpConnector",
      "http": {
        "baseUrl": "https://api.payments.example/v1",
        "baseUrlProperty": "paymentsBaseUrl",
        "auth": { "type": "bearer", "secretEnvVar": "NPDEV_CONNECTOR_PAYMENTS_TOKEN" },
        "headers": { "Accept": "application/json" },
        "timeoutMs": 10000,
        "maxRetries": 1
      },
      "operations": [
        {
          "name": "getCustomer",
          "sideEffects": "reads",
          "policy": { "retryCount": 2, "retryDelayMs": 500 },
          "http": {
            "method": "GET",
            "path": "/customers/{customerId}",
            "params": ["customerId"],
            "response": { "pointer": "/data" }
          }
        },
        {
          "name": "createCharge",
          "sideEffects": "external",
          "http": {
            "method": "POST",
            "path": "/charges",
            "params": ["amount", "currency", "customerId"],
            "query": { "expand": "customer" },
            "bodyEncoding": "json",
            "body": { "amount": "{amount}", "currency": "{currency}", "customer": "{customerId}" },
            "response": { "pointer": "/id" }
          }
        }
      ]
    }
  ],
  "bindings": [ { "capability": "payments", "adapter": "http-connector" } ]
}
```

Connector level (`capabilities[].http`):

| Key | Meaning |
|---|---|
| `baseUrl` | Absolute `http(s)://` root of the remote API. |
| `baseUrlProperty` | Name of a declared `properties[]` entry. When it resolves to a non-blank value for the calling tenant it overrides `baseUrl` (sandbox vs production per tenant). One of the two is required. |
| `auth` | See "Secrets" below. Absent means `none`. |
| `headers` | Static headers sent on every call. |
| `timeoutMs` | Per-attempt deadline, 1..120000, default 10000. |
| `maxRetries` | Extra attempts for idempotent methods only, 0..5, default 0. |

Operation level (`operations[].http`, the operation must be an object, not a bare string):

| Key | Meaning |
|---|---|
| `method` | `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. Required. |
| `path` | Starts with `/`. `{name}` placeholders are filled from `params`. Required. |
| `params` | Names, in the order the caller passes arguments. Every `{name}` used anywhere in the operation must be listed here (the validator checks it). |
| `query` | Query parameters; a value that renders empty drops its key. |
| `headers` | Extra headers for this operation; values may use `{name}`. |
| `body` | A JSON template (object) or a string. Ignored for `GET` and `DELETE`. |
| `bodyEncoding` | `json` (default) or `form` (`application/x-www-form-urlencoded`, flat object only). |
| `response.pointer` | JSON pointer (`/data/id`) of the value to return. Empty or absent returns the whole body. A non-JSON body is returned as text. |

Body template rules: a string that is exactly `"{p}"` becomes the typed value of param `p` (number,
object, array); a string containing `{p}` among other text is interpolated as text; a value that
renders absent is omitted from the object. `"body": "{payload}"` passes the whole value of param
`payload` through.

The operation `policy` (`retryCount`, `retryDelayMs`, `circuitOpenAfterFailures`, ...) is the
ordinary capability policy applied by the flow engine on `capabilityCall` steps, on top of the
connector's own `maxRetries`.

## Calling it

Flow step: `args` is an ARRAY and positional (or a single map that carries the declared params),
`output` names the variable that receives the result.

```json
{ "name": "charge", "type": "capabilityCall", "capability": "payments", "operation": "createCharge",
  "args": ["$input.amount", "$input.currency", "$input.customerId"], "output": "$charge" }
```

Procedure step: `args` is an OBJECT whose entries are taken in declared order, `target` names the
variable that receives the result.

```json
{ "name": "charge", "type": "capabilityCall", "capability": "payments", "operation": "createCharge",
  "args": { "amount": "$input.amount", "currency": "$input.currency", "customerId": "$input.customerId" },
  "target": "charge" }
```

Positional binding: argument `i` is bound to `params[i]`. One map argument is treated as named
arguments when it carries every declared param (or the operation declares two or more).

## Secrets

`auth.type` is one of:

| Type | Required keys | What is sent |
|---|---|---|
| `none` | none | nothing |
| `bearer` | `secretEnvVar` | `Authorization: Bearer <secret>` |
| `basic` | `usernameEnvVar`; optional `secretEnvVar` (absent = empty password) | `Authorization: Basic ...` |
| `apiKeyHeader` | `name`, `secretEnvVar` | header `<name>: <secret>` |
| `apiKeyQuery` | `name`, `secretEnvVar` | query parameter `<name>=<secret>` |

`secretEnvVar` and `usernameEnvVar` are environment variable NAMES. The model never holds a secret
value. The runtime looks the name up through the Spring `Environment` at call time, so an OS
environment variable, a `-D` option or an application property with that exact name all work, for
example `NPDEV_CONNECTOR_PAYMENTS_TOKEN`. If a configured secret is missing the call fails closed with
`CONNECTOR_SECRET_NOT_CONFIGURED` and nothing is sent. Secrets and resolved URLs are never written to
logs or failure details; failures name the path template (`/customers/{customerId}`), not the
resolved URL.

## Egress rules

Applied before any request is sent (`CONNECTOR_EGRESS_DENIED` otherwise):

1. `npdev.connectors.enabled` (default `true`); `false` refuses every connector call.
2. The final request host must equal the host of the declared base URL, so no argument can steer
   the call to another host. Redirects are never followed.
3. The scheme must be `https`, unless the host is loopback (`localhost`, `127.0.0.1`, `::1`) or
   `npdev.connectors.allow-insecure-http=true`.
4. When `npdev.connectors.allowed-hosts` (comma list) is non-empty the host must be in it. Set it
   by environment, for example `NPDEV_CONNECTORS_ALLOWED_HOSTS=api.payments.example`.

A path parameter that is blank, `.` or `..` is refused (`CONNECTOR_REQUEST_INVALID`); each path
parameter is percent-encoded as a single segment.

## Failure codes

Every failure is a classified capability error, so the flow's retry/circuit policy and `onFailure`
branches apply.

| Code | Kind | When |
|---|---|---|
| `CONNECTOR_HTTP_<status>` | `AUTH` (401, 403), `NOT_FOUND` (404), `TIMEOUT` (408), `RATE_LIMIT` (429), `TRANSIENT` (5xx), otherwise `CONTRACT` | Non-2xx answer. Details: capability, operation, method, path template, a body excerpt of at most 500 characters. |
| `CONNECTOR_TIMEOUT` | `TIMEOUT` | No (complete) response within `timeoutMs`. |
| `CONNECTOR_UNREACHABLE` | `TRANSIENT` | I/O failure (connection refused, reset). |
| `CONNECTOR_SECRET_NOT_CONFIGURED` | `CONTRACT` | A required environment variable is missing or blank. |
| `CONNECTOR_EGRESS_DENIED` | `CONTRACT` | An egress rule above refused the call. |
| `CONNECTOR_REQUEST_INVALID` | `CONTRACT` | Bad path parameter or unusable body. |
| `CONNECTOR_BASE_URL_UNRESOLVED` | `CONTRACT` | No literal `baseUrl` and the property resolved to nothing. |
| `CONNECTOR_RESPONSE_POINTER_MISSING` | `CONTRACT` | The response has nothing at `response.pointer`. |
| `CONNECTOR_RESPONSE_TOO_LARGE` | `CONTRACT` | Response larger than 2 MB. |
| `CONNECTOR_UNKNOWN`, `CONNECTOR_OPERATION_UNKNOWN` | `CONTRACT` | The call names a connector or operation the model does not declare. |
| `CONNECTOR_INTERRUPTED`, `CONNECTOR_INTERNAL_ERROR` | `TRANSIENT`, `PERMANENT` | Thread interrupted, or an unexpected runtime fault. |

## Limits

- Response body: 2 MB.
- Deadline: `timeoutMs` per attempt (default 10 s, maximum 120 s); it also bounds reading the body.
- No redirects.
- The adapter's own retry (`maxRetries`) applies only to `GET`, `PUT`, `DELETE`, and only for
  I/O failures, 429 and 5xx. `POST` and `PATCH` are never retried by the adapter; set the flow-step
  `policy.retryCount` deliberately if the remote call is safe to repeat.
- Whole numbers in the compiled `http` blocks read back as `Long` or `Integer` depending on whether
  the model came from source or from canonical JSON; the runtime treats both as numbers.

## Worked example: a loopback witness

`NPDevSamples/dsl-conformance-max` declares a connector `selfApi` that calls the app's own
`/actuator/health`, so it needs no external service:

```json
{
  "name": "selfApi", "type": "HttpConnector",
  "http": {
    "baseUrlProperty": "selfApiBaseUrl",
    "auth": { "type": "apiKeyHeader", "name": "X-Connector-Probe", "secretEnvVar": "NPDEV_CONNECTOR_SELFAPI_KEY" },
    "timeoutMs": 5000, "maxRetries": 1
  },
  "operations": [
    { "name": "health", "sideEffects": "reads",
      "http": { "method": "GET", "path": "/actuator/health", "response": { "pointer": "/status" } } }
  ]
}
```

With flow `ConnectorSelfCheckFlow` (`capabilityCall selfApi.health`, `output: "$health"`):

- without `NPDEV_CONNECTOR_SELFAPI_KEY` the flow fails with `CONNECTOR_SECRET_NOT_CONFIGURED`;
- with it set, the flow returns `"UP"`;
- with `NPDEV_CONNECTORS_ALLOWED_HOSTS=api.example.com` it fails with `CONNECTOR_EGRESS_DENIED`.

Run it with `POST /api/v1/flows/ConnectorSelfCheckFlow/execute` and the app's API key. The
`selfApiBaseUrl` default is the sample's fixed port 8099; override it with the tenant property if the
app listens elsewhere.
