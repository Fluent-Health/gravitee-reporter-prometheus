# Health-check + Prometheus example

A minimal, fully **file-driven** Gravitee APIM 4.9 stack (no environment
variables, no Elasticsearch) that loads the
`gravitee-reporter-prometheus` plugin and exposes health-check gauges
on `/metrics`. The example API stays up even with a health check
configured — the trick is in how the check is wired.

## What you get

| URL                                  | What                                            |
|--------------------------------------|-------------------------------------------------|
| `http://localhost:8082/example`      | Proxied API → httpbin `/anything`               |
| `http://localhost:9090/metrics`      | Prometheus scrape endpoint on the gateway       |
| `http://localhost:8083/management/v2`| Management API (`admin` / `admin`)              |

## Run it

```bash
./up.sh        # boots compose stack + deploys the demo API
./verify.sh    # asserts /example returns 200 and /metrics is populated
./down.sh      # tears everything down (containers + volumes)
```

`up.sh` looks for a plugin ZIP in this order:

1. Argument (`./up.sh /path/to/gravitee-reporter-prometheus-x.y.z.zip`)
2. Newest `../../target/gravitee-reporter-prometheus-*.zip` (local build)
3. Download `RELEASE_TAG=0.2.0` from GitHub Releases

## How it's wired

### Config files only — no env vars

Both `gravitee.yml` files are derived from the images' bundled
defaults (`/opt/graviteeio-*/config/gravitee.yml`) and replace those
files at mount time. Keeping the full bundled file is intentional:
every required key (JWT secret, default `admin/admin` memory auth,
listener config, secure-headers policy, …) stays present, and the
delta is small and visible.

The targeted edits, per file:

| File                                | Change                                                                                                                |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `gateway/gravitee.yml`              | `ds.mongodb.host` → `mongodb`; `reporters.elasticsearch.enabled: false`; `reporters.prometheus.{enabled, port}`; `plugins.path` adds `plugins-ext/`; `services.core.http.host: 0.0.0.0` and `authentication.type: none` so the in-container docker healthcheck works |
| `management-api/gravitee.yml`       | `ds.mongodb.host` → `mongodb`; `analytics.type: none`; `services.core.http.host: 0.0.0.0` / `authentication.type: none` |

Nothing in `docker-compose.yml` sets `gravitee_*` env vars; the
gateway and management API read everything from their mounted YAML.

### The plugin

`up.sh` drops the plugin ZIP into `plugins/`, and the gateway mounts
that directory at `/opt/graviteeio-gateway/plugins-ext/`. Combined
with the `plugins.path` entry in `gravitee.yml`, the reporter is
loaded at startup. You'll see this line in `docker logs gravitee-prom-gw`:

```
Install plugin: prometheus [io.gravitee.reporter.prometheus.PrometheusReporter]
Prometheus reporter started — /metrics available on port 9090
```

### Why the health check doesn't take the API down

In Gravitee V4 the health check service emits `EndpointStatus` events
(which the Prometheus reporter turns into `gravitee_api_endpoint_up`
gauges and `gravitee_api_health_checks_total` counters) but, for a
single-endpoint API, **failing health checks do not stop traffic**:
the gateway keeps routing to the only endpoint it has. The health
check is therefore observational; you'll see it in metrics, but you
won't see your API return 503 just because the probe failed.

To make the probe itself reliable — so the gauge actually reflects
"is my backend reachable" rather than churning — point the health
check at a stable URL on the backend:

```jsonc
// apis/health-check-api.json (excerpt)
"endpoints": [{
  "configuration": { "target": "http://httpbin:8080/anything" },
  //                                       ↑
  // Real request traffic goes through /anything (echoes whatever you send)
  "services": {
    "healthCheck": {
      "enabled": true,
      "type": "http-health-check",
      "overrideConfiguration": true,
      "configuration": {
        "target":   "http://httpbin:8080/status/200",
        //                                ↑
        // Health-check probe is a separate URL — always returns 200
        "schedule": "*/10 * * * * *",
        "method":   "GET"
      }
    }
  }
}]
```

Key points:

1. **`healthCheck.configuration.target` is a full URL**, not a path
   suffix. It can point at any URL the gateway can resolve — including
   a dedicated `/healthz` on the same backend, a static file, or a
   sidecar.
2. **API traffic uses `endpoints[].configuration.target`** independently.
   `/example` on the gateway → `/anything` on the backend. The two
   targets are evaluated separately.
3. **Omit the `assertion` field** unless you really need custom logic.
   Without it Gravitee accepts any 2xx response as a success — exactly
   what you want for a stable probe. A bad EL assertion silently
   classifies every probe as failure (the bug this example originally
   hit before being fixed).

#### What *not* to do

* **Don't probe an authenticated path** — every probe will return 401
  and the result counter will fill the `failure` bucket forever.
* **Don't probe a CPU-heavy endpoint** — you'll add load every
  schedule tick.
* **Don't omit `overrideConfiguration: true`** when the service is
  defined inside the endpoint — without it the group-level (empty)
  service config silently wins.
* **Don't tighten the schedule below ~5 s** unless your backend is
  built for it; the default `*/10 * * * * *` (every 10 s) is a
  reasonable starting point.

### Reading the metrics

`./verify.sh` runs the assertions; the interesting lines from
`/metrics` are:

```
# Per-endpoint up/down gauge
gravitee_api_endpoint_up{api_name="Stable Health Check Example",endpoint="primary"} 1.0

# Cumulative success / failure counts
gravitee_api_health_checks_total{api_name="Stable Health Check Example",endpoint="primary",result="success"} 6

# Request-side counters tagged with status
gravitee_api_requests_total{api_name="Stable Health Check Example",method="GET",status="200"} 5
```

If you want to *see* the failure path, change the API definition's
probe path to `/status/500`, `./up.sh` again, and watch
`gravitee_api_endpoint_up` flip to `0.0` while `/example` starts
returning 503 — exactly the trap this example is designed to avoid.

## File layout

```
examples/health-check-prometheus/
├── README.md
├── docker-compose.yml
├── gateway/gravitee.yml              ← gateway config (mounted)
├── management-api/gravitee.yml       ← management API config (mounted)
├── apis/health-check-api.json        ← V4 API definition with health check
├── plugins/                          ← drop the plugin ZIP here (up.sh handles it)
├── up.sh                             ← boot + deploy
├── verify.sh                         ← assert the contract
└── down.sh                           ← teardown
```
