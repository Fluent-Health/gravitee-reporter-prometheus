# Gravitee Reporter Prometheus

A Gravitee APIM reporter plugin that converts per-request gateway events into Prometheus metrics and exposes them on a `/metrics` HTTP endpoint. Grafana Alloy running in-cluster scrapes each gateway pod and forwards the data to Grafana Cloud. No external credentials are required — the endpoint is cluster-local only.

A project by [Fluent Health](https://github.com/Fluent-Health).

## Quick Links

- [GitHub Releases](https://github.com/Fluent-Health/gravitee-reporter-prometheus/releases)

## Development

Prerequisites: [`asdf`](https://asdf-vm.com) and Docker.

```bash
asdf plugin add java
asdf install
mvn verify
```

The `.tool-versions` file pins Java 21 (Temurin). `mvn verify` compiles, runs the unit test suite, and produces the plugin ZIP at `target/gravitee-reporter-prometheus-*.zip`.

To load the plugin into a local Gravitee gateway, copy the ZIP to the gateway's `plugins-ext/` directory and add the following to `gravitee.yml`:

```yaml
reporters:
  prometheus:
    enabled: true
    port: 9090
```

The `/metrics` endpoint is then available at `http://localhost:9090/metrics`.

## Testing

**Unit tests** (no Docker required):

```bash
mvn verify
```

**Integration tests** (Docker required — pulls Gravitee APIM 4.9, MongoDB 7, and go-httpbin images, ~3–5 minutes):

```bash
mvn verify --activate-profiles integration-test
```

The integration test starts a full Gravitee stack in containers, mounts the plugin ZIP, creates two V4 HTTP proxy APIs, sends traffic, and asserts on Prometheus counter and histogram values scraped from the live `/metrics` endpoint.

## Deployment

Releases follow **semver tagging**. To publish a new release:

1. Create a GitHub Release with a semver tag (e.g. `v1.2.0`).
2. The `release.yml` workflow sets the Maven version, builds the plugin, and attaches the ZIP to the release automatically.
3. To deploy, copy the released ZIP into the Gravitee gateway `plugins-ext/` directory and restart the gateway pod. The plugin is picked up on startup.

## Additional Info

### Metrics reference

| Metric | Type | Labels |
|---|---|---|
| `gravitee_api_requests_total` | Counter | `api_name`, `method`, `status` |
| `gravitee_api_request_duration_milliseconds` | Histogram | `api_name` — buckets: 50, 100, 250, 500, 1000, 2500, 5000 ms |
| `gravitee_api_errors_total` | Counter | `api_name`, `status` — 4xx/5xx only |
| `gravitee_api_request_size_bytes` | Histogram | `api_name` |
| `gravitee_api_response_size_bytes` | Histogram | `api_name` |
| `gravitee_api_endpoint_up` | Gauge | `api_name`, `endpoint` — 1.0 up, 0.0 down |
| `gravitee_api_health_checks_total` | Counter | `api_name`, `endpoint`, `result` (success/failure) |

### API Availability (Health Checks)

To emit `gravitee_api_endpoint_up` gauges, you must configure a **Health Check** service for your API. Below is a minimal configuration using the [`gravitee-io/apim`](https://registry.terraform.io/providers/gravitee-io/apim/latest) Terraform provider for a V4 API:

```hcl
resource "apim_apiv4" "my_api" {
  name = "My Protected API"
  # ... other fields ...

  endpoint_groups = [{
    name = "default"
    type = "http-proxy"
    endpoints = [{
      name          = "primary"
      type          = "http-proxy"
      configuration = jsonencode({ target = "http://my-backend:8080" })

      services = {
        health_check = {
          enabled                = true
          type                   = "http-health-check" # Mandatory for V4 HTTP APIs
          override_configuration = true                # Required when defined at endpoint level
          configuration = jsonencode({
            schedule = "*/30 * * * * *" # CRON: every 30 seconds
            method   = "GET"
            path     = "/health"        # Appended to endpoint target
          })
        }
      }
    }]
  }]
}
```

**Why these settings?**
*   **`type = "http-health-check"`**: Specifies the V4 API service plugin responsible for monitoring HTTP backends.
*   **`override_configuration = true`**: Mandatory if configuring the service inside an `endpoint` block to ensure it replaces any inherited (or empty) group-level service.
*   **`schedule`**: A mandatory CRON expression. The Gateway will only emit status events (and thus Prometheus gauges) when this probe runs.
*   **`path`**: The specific endpoint to probe. If omitted, it probes the base `target`.


### Architecture

```
Gravitee Gateway
  └── PrometheusReporter  (AbstractService<Reporter> + Reporter)
        ├── canHandle()   Metrics, EndpointStatus → true; all others → false
        ├── report()      delegates to ApiMetrics
        └── HTTPServer    Prometheus built-in HTTP server (started in doStart, closed in doStop)

ApiMetrics
  └── PrometheusRegistry  owns all 7 metric instruments; thread-safe
```

`api_name` uses the human-readable API name from `getApiName()` rather than the internal Gravitee UUID so that Grafana dashboards display meaningful labels. If an API is renamed in Gravitee, existing Prometheus series retain the old label value until they expire from retention.

The `/metrics` endpoint is unauthenticated. Network isolation (cluster policy) and Grafana Alloy running in-cluster provide sufficient security for this use case — no bearer token auth is implemented.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Report security issues via [GitHub private vulnerability reporting](https://github.com/Fluent-Health/gravitee-reporter-prometheus/security/advisories/new).

## License

Apache 2.0 — see [LICENSE](./LICENSE).
