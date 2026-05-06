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
package io.gravitee.reporter.prometheus;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.metric.Metrics;
import java.time.Instant;

public final class PrometheusTestSupport {

  private PrometheusTestSupport() {}

  public static Metrics metrics(int status) {
    Metrics m = new Metrics();
    m.setApiId("api-123");
    m.setApiName("Test API");
    m.setHttpMethod(HttpMethod.GET);
    m.setUri("/api/v1/users/42");
    m.setStatus(status);
    m.setRequestContentLength(128);
    m.setResponseContentLength(512);
    m.setGatewayResponseTimeMs(42);
    m.setTimestamp(Instant.parse("2024-01-15T12:00:00Z").toEpochMilli());
    return m;
  }

  public static Metrics metricsNoSizes(int status) {
    Metrics m = metrics(status);
    m.setRequestContentLength(0);
    m.setResponseContentLength(0);
    return m;
  }

  public static EndpointStatus endpointStatus(boolean available) {
    EndpointStatus s = EndpointStatus.forEndpoint(
      "api-123",
      "Test API",
      "https://backend.example.com/health"
    )
      .on(System.currentTimeMillis())
      .build();
    s.setAvailable(available);
    s.setResponseTime(100);
    return s;
  }
}
