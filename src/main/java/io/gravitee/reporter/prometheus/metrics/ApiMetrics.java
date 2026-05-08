/*
 * Copyright © 2026 Fluent Health (https://fluentinhealth.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.reporter.prometheus.metrics;

import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

public class ApiMetrics {

  private final PrometheusRegistry registry;

  private final Counter requestsTotal;
  private final Histogram requestDuration;
  private final Histogram proxyDuration;
  private final Counter errorsTotal;
  private final Histogram requestSize;
  private final Histogram responseSize;
  private final Gauge endpointUp;
  private final Counter healthChecksTotal;

  public ApiMetrics(PrometheusRegistry registry) {
    this.registry = registry;

    requestsTotal = Counter.builder()
      .name("gravitee_api_requests_total")
      .help("Total number of API requests processed by the gateway")
      .labelNames(
        "api_name",
        "method",
        "status",
        "plan_id",
        "plan_name",
        "application_id",
        "application_name"
      )
      .register(registry);

    requestDuration = Histogram.builder()
      .name("gravitee_api_request_duration_milliseconds")
      .help(
        "Total API request duration (including gateway overhead) in milliseconds"
      )
      .labelNames("api_name")
      .classicUpperBounds(50, 100, 250, 500, 1000, 2500, 5000)
      .register(registry);

    proxyDuration = Histogram.builder()
      .name("gravitee_api_proxy_duration_milliseconds")
      .help("Backend (upstream) response time in milliseconds")
      .labelNames("api_name")
      .classicUpperBounds(50, 100, 250, 500, 1000, 2500, 5000)
      .register(registry);

    errorsTotal = Counter.builder()
      .name("gravitee_api_errors_total")
      .help("Total number of API requests resulting in a 4xx or 5xx response")
      .labelNames(
        "api_name",
        "status",
        "plan_id",
        "plan_name",
        "application_id",
        "application_name"
      )
      .register(registry);

    requestSize = Histogram.builder()
      .name("gravitee_api_request_size_bytes")
      .help("API request body size in bytes")
      .labelNames("api_name")
      .register(registry);

    responseSize = Histogram.builder()
      .name("gravitee_api_response_size_bytes")
      .help("API response body size in bytes")
      .labelNames("api_name")
      .register(registry);

    endpointUp = Gauge.builder()
      .name("gravitee_api_endpoint_up")
      .help("Whether the API backend endpoint is available (1=up, 0=down)")
      .labelNames("api_name", "endpoint")
      .register(registry);

    healthChecksTotal = Counter.builder()
      .name("gravitee_api_health_checks_total")
      .help("Total number of API endpoint health checks performed")
      .labelNames("api_name", "endpoint", "result")
      .register(registry);
  }

  public void recordRequest(Metrics m) {
    String apiName = m.getApiName() != null ? m.getApiName() : "unknown";
    String method = m.getHttpMethod() != null
      ? m.getHttpMethod().name()
      : "UNKNOWN";
    String status = String.valueOf(m.getStatus());
    String planId = m.getPlanId() != null ? m.getPlanId() : "unknown";
    String planName = m.getPlanName() != null ? m.getPlanName() : "unknown";
    String applicationId = m.getApplicationId() != null
      ? m.getApplicationId()
      : "unknown";
    String applicationName = m.getApplicationName() != null
      ? m.getApplicationName()
      : "unknown";

    requestsTotal
      .labelValues(
        apiName,
        method,
        status,
        planId,
        planName,
        applicationId,
        applicationName
      )
      .inc();
    requestDuration.labelValues(apiName).observe(m.getGatewayResponseTimeMs());

    if (m.getProxyResponseTimeMs() > 0) {
      proxyDuration.labelValues(apiName).observe(m.getProxyResponseTimeMs());
    }

    if (m.getStatus() >= 400) {
      errorsTotal
        .labelValues(
          apiName,
          status,
          planId,
          planName,
          applicationId,
          applicationName
        )
        .inc();
    }
    if (m.getRequestContentLength() > 0) {
      requestSize.labelValues(apiName).observe(m.getRequestContentLength());
    }
    if (m.getResponseContentLength() > 0) {
      responseSize.labelValues(apiName).observe(m.getResponseContentLength());
    }
  }

  public void recordHealthCheck(EndpointStatus es) {
    String apiName = es.getApiName() != null ? es.getApiName() : "unknown";
    String endpoint = es.getEndpoint() != null ? es.getEndpoint() : "unknown";

    endpointUp.labelValues(apiName, endpoint).set(es.isAvailable() ? 1.0 : 0.0);
    healthChecksTotal
      .labelValues(apiName, endpoint, es.isAvailable() ? "success" : "failure")
      .inc();
  }

  public PrometheusRegistry getRegistry() {
    return registry;
  }
}
