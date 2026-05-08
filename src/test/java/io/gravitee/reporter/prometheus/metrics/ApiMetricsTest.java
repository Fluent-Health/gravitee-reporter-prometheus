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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.prometheus.PrometheusTestSupport;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiMetricsTest {

  private PrometheusRegistry registry;
  private ApiMetrics apiMetrics;

  @BeforeEach
  void setUp() {
    registry = new PrometheusRegistry();
    apiMetrics = new ApiMetrics(registry);
  }

  @Test
  void recordRequest_incrementsRequestsTotal() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_requests_total{api_name=\"Test API\",application_id=\"app-789\",application_name=\"My App\",method=\"GET\",plan_id=\"plan-456\",plan_name=\"Gold Plan\",status=\"200\"} 1.0"
    );
  }

  @Test
  void recordRequest_multipleCallsAccumulate() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_requests_total{api_name=\"Test API\",application_id=\"app-789\",application_name=\"My App\",method=\"GET\",plan_id=\"plan-456\",plan_name=\"Gold Plan\",status=\"200\"} 2.0"
    );
  }

  @Test
  void recordRequest_observesDuration() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_request_duration_milliseconds_count{api_name=\"Test API\"} 1"
    );
  }

  @Test
  void recordRequest_observesProxyDuration() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_proxy_duration_milliseconds_count{api_name=\"Test API\"} 1"
    );
  }

  @Test
  void recordRequest_observesRequestAndResponseSizes() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_request_size_bytes_count{api_name=\"Test API\"} 1"
    );
    assertThat(out).contains(
      "gravitee_api_response_size_bytes_count{api_name=\"Test API\"} 1"
    );
  }

  @Test
  void recordRequest_skipsSizeObservationWhenZero() {
    apiMetrics.recordRequest(PrometheusTestSupport.metricsNoSizes(200));
    String out = scrape();
    assertThat(out).doesNotContain("gravitee_api_request_size_bytes_count");
    assertThat(out).doesNotContain("gravitee_api_response_size_bytes_count");
  }

  @Test
  void recordRequest_incrementsErrorsForClientError() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(404));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_errors_total{api_name=\"Test API\",application_id=\"app-789\",application_name=\"My App\",plan_id=\"plan-456\",plan_name=\"Gold Plan\",status=\"404\"} 1.0"
    );
  }

  @Test
  void recordRequest_incrementsErrorsForServerError() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(500));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_errors_total{api_name=\"Test API\",application_id=\"app-789\",application_name=\"My App\",plan_id=\"plan-456\",plan_name=\"Gold Plan\",status=\"500\"} 1.0"
    );
  }

  @Test
  void recordRequest_doesNotIncrementErrorsFor2xx() {
    apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
    String out = scrape();
    assertThat(out).doesNotContain("gravitee_api_errors_total");
  }

  @Test
  void recordRequest_usesUnknownForNullApiName() {
    var m = PrometheusTestSupport.metrics(200);
    m.setApiName(null);
    apiMetrics.recordRequest(m);
    String out = scrape();
    assertThat(out).contains("api_name=\"unknown\"");
  }

  @Test
  void recordHealthCheck_setsEndpointUpGaugeToOneWhenAvailable() {
    apiMetrics.recordHealthCheck(PrometheusTestSupport.endpointStatus(true));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_endpoint_up{api_name=\"Test API\",endpoint=\"https://backend.example.com/health\"} 1.0"
    );
  }

  @Test
  void recordHealthCheck_setsEndpointUpGaugeToZeroWhenDown() {
    apiMetrics.recordHealthCheck(PrometheusTestSupport.endpointStatus(false));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_endpoint_up{api_name=\"Test API\",endpoint=\"https://backend.example.com/health\"} 0.0"
    );
  }

  @Test
  void recordHealthCheck_updatesGaugeOnSubsequentCalls() {
    apiMetrics.recordHealthCheck(PrometheusTestSupport.endpointStatus(true));
    apiMetrics.recordHealthCheck(PrometheusTestSupport.endpointStatus(false));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_endpoint_up{api_name=\"Test API\",endpoint=\"https://backend.example.com/health\"} 0.0"
    );
  }

  @Test
  void recordHealthCheck_incrementsSuccessCounter() {
    apiMetrics.recordHealthCheck(PrometheusTestSupport.endpointStatus(true));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_health_checks_total{api_name=\"Test API\",endpoint=\"https://backend.example.com/health\",result=\"success\"} 1.0"
    );
  }

  @Test
  void recordHealthCheck_incrementsFailureCounter() {
    apiMetrics.recordHealthCheck(PrometheusTestSupport.endpointStatus(false));
    String out = scrape();
    assertThat(out).contains(
      "gravitee_api_health_checks_total{api_name=\"Test API\",endpoint=\"https://backend.example.com/health\",result=\"failure\"} 1.0"
    );
  }

  @Test
  void getRegistry_returnsTheSameRegistryPassedInConstructor() {
    assertThat(apiMetrics.getRegistry()).isSameAs(registry);
  }

  private String scrape() {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      new PrometheusTextFormatWriter(false).write(baos, registry.scrape());
      return baos.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
