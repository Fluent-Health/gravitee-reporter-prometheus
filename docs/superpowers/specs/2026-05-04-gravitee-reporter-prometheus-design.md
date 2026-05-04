# gravitee-reporter-prometheus — Design Spec

**Date:** 2026-05-04
**Issue:** [Fluent-Health/project-management#3586](https://github.com/Fluent-Health/project-management/issues/3586)
**Status:** Approved

---

## Goal

Build a native Gravitee APIM reporter plugin that converts per-request reportables into Prometheus metrics, giving Grafana a real-time per-API metrics data source. The plugin exposes a `/metrics` HTTP endpoint (Prometheus text format) on a configurable port on each gateway pod.

---

## Reference Implementation

Model directly on [`ivank/gravitee-reporter-gcloud`](https://github.com/ivank/gravitee-reporter-gcloud): same Maven/Java 21 structure, same GitHub Actions CI + release workflow, same Spring wiring conventions.

---

## Architecture & Components

Four production classes:

```
src/main/java/io/gravitee/reporter/prometheus/
  PrometheusReporter.java                          # AbstractService<Reporter> + Reporter
  PrometheusReporterConfiguration.java             # @Value bindings for reporters.prometheus.*
  metrics/
    ApiMetrics.java                                # owns PrometheusRegistry + all metric instruments
  spring/
    PrometheusReporterSpringConfiguration.java     # @Configuration — wires all beans
```

**Spring wiring:** Java `@Configuration` class (no XML), matching gcloud's actual pattern.

**Prometheus client:** New official client — `io.prometheus:prometheus-metrics-core` + `io.prometheus:prometheus-metrics-exporter-httpserver`.

**HTTP server:** Prometheus client's built-in `HTTPServer` started in `doStart()`, closed in `doStop()`. Handles Prometheus text/protobuf content negotiation automatically. Bound to the configured port (default 9090).

Resources:
- `src/main/resources/plugin.properties` — plugin identity
- `src/main/resources/gravitee.json` — JSON Schema for management UI config panel
- `src/main/resources/assembly/plugin-assembly.xml` — Maven assembly descriptor (JAR + bundled deps → ZIP)

**Copyright:** All source files use `Fluent Health` as the copyright holder.

---

## Metrics

### Request metrics (from `Metrics` reportable — `m.getApiName()`)

| Metric | Type | Labels |
|---|---|---|
| `gravitee_api_requests_total` | Counter | `api_name`, `method`, `status` |
| `gravitee_api_request_duration_milliseconds` | Histogram | `api_name` — buckets: 50, 100, 250, 500, 1000, 2500, 5000 |
| `gravitee_api_errors_total` | Counter | `api_name`, `status` — 4xx/5xx only |
| `gravitee_api_request_size_bytes` | Histogram | `api_name` |
| `gravitee_api_response_size_bytes` | Histogram | `api_name` |

### Health check metrics (from `EndpointStatus` reportable — `es.getApiName()`)

| Metric | Type | Labels |
|---|---|---|
| `gravitee_api_endpoint_up` | Gauge | `api_name`, `endpoint` — 1.0 if available, 0.0 if down |
| `gravitee_api_health_checks_total` | Counter | `api_name`, `endpoint`, `result` (success/failure) |

**Label note:** `api_name` uses `getApiName()` (human-readable) rather than the internal UUID. If an API is renamed in Gravitee, existing Prometheus series persist with the old label value until they expire from retention.

**Health check update policy:** Update both the Gauge and counter on **every** `EndpointStatus` event (not just transitions), so the Gauge reflects current state immediately after a gateway restart.

---

## Data Flow

`PrometheusReporter` is a thin traffic cop — no payload mapping:

```
canHandle():
  Metrics        → true (when enabled)
  EndpointStatus → true (when enabled)
  all others     → false

report():
  Metrics        → apiMetrics.recordRequest(m)
  EndpointStatus → apiMetrics.recordHealthCheck(es)
```

`ApiMetrics.recordRequest(Metrics m)`:
- `requests_total.labelValues(apiName, method, status).inc()`
- `duration_histogram.labelValues(apiName).observe(gatewayResponseTimeMs)`
- if `status >= 400`: `errors_total.labelValues(apiName, status).inc()`
- if `requestContentLength > 0`: `request_size_histogram.labelValues(apiName).observe(...)`
- if `responseContentLength > 0`: `response_size_histogram.labelValues(apiName).observe(...)`

`ApiMetrics.recordHealthCheck(EndpointStatus es)`:
- `endpoint_up.labelValues(apiName, endpoint).set(es.isAvailable() ? 1.0 : 0.0)`
- `health_checks_total.labelValues(apiName, endpoint, result).inc()`

---

## Configuration

`PrometheusReporterConfiguration` binds to `reporters.prometheus.*`:

```yaml
gateway:
  reporters:
    prometheus:
      enabled: true
      port: 9090
```

Two fields only — `enabled` (default `true`) and `port` (default `9090`). The `gravitee.json` exposes both to the Gravitee management UI.

`plugin.properties`:
```properties
id=prometheus
name=Gravitee.io APIM - Reporter - Prometheus
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.prometheus.PrometheusReporter
type=reporter
```

---

## Testing

### Unit tests

- **`PrometheusReporterTest`** — mirrors `GCloudReporterTest`: tests `canHandle()` for each reportable type and enabled/disabled states; verifies `report()` delegates to `ApiMetrics` correctly (mocked).
- **`ApiMetricsTest`** — core logic: fire synthetic `Metrics` and `EndpointStatus` values, assert counter/histogram/gauge values directly on the `PrometheusRegistry`. No mocking — registry is real and in-process.

### Integration test (`GraviteeReporterIT`)

Same full-stack Testcontainers pattern as `gravitee-reporter-gcloud`:

1. Start MongoDB + Management API + Gateway containers with plugin ZIP mounted
2. Create two V4 HTTP proxy APIs via the Management REST API (one 200 endpoint, one 500 endpoint)
3. Wait for gateway sync; send requests through both APIs
4. HTTP-GET `http://localhost:MAPPED_PORT/metrics`; parse Prometheus text format; assert:
   - `gravitee_api_requests_total` incremented with correct `api_name`/`method`/`status` labels
   - `gravitee_api_errors_total` present for the 500 API
   - `gravitee_api_request_duration_milliseconds` histogram present

No external credentials required — `/metrics` is served locally by the plugin.

IT profile (`-Pintegration-test`) runs via `maven-failsafe-plugin`, matching gcloud's CI pattern.

### CI / Release workflows

- **`ci.yml`**: build + unit tests on every push/PR; integration tests as a separate job (same structure as gcloud)
- **`release.yml`**: on GitHub release published → set Maven version → `mvn verify` → upload ZIP to the release

---

## Documentation

- **README**: Written using the `fh:developer-standards` skill (standard Fluent Health repo structure: purpose, quickstart, config reference, contributing guide)
- **`catalog-info.yaml`**: `Component` of type `library`, owner `group:backend`, system `apim`

---

## Security

The `/metrics` endpoint is unauthenticated. Grafana Cloud scraping is done via **Grafana Alloy running in-cluster**, which scrapes `http://gateway-pod:PORT/metrics` locally and pushes to Grafana Cloud via remote_write. The port is never internet-exposed; network policy at the cluster level provides sufficient isolation.

Bearer token auth is explicitly out of scope for this reason.

---

## Out of Scope

- Message metrics (`MessageMetrics` reportable) — not needed for the Prometheus use case
- Full request/response logs (`Log` reportable)
- Node monitor events (`Monitor` reportable)
- Metric prefix configuration — names are fixed; renaming is a breaking change for dashboards
