/*
 * Copyright © 2015 Fluent Health (https://fluentinhealth.com)
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

import io.gravitee.common.service.AbstractService;
import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.Reporter;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.prometheus.metrics.ApiMetrics;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class PrometheusReporter
  extends AbstractService<Reporter>
  implements Reporter {

  private static final Logger log = LoggerFactory.getLogger(
    PrometheusReporter.class
  );

  @Autowired
  private PrometheusReporterConfiguration cfg;

  @Autowired
  private ApiMetrics apiMetrics;

  private HTTPServer httpServer;

  @Override
  protected void doStart() throws Exception {
    super.doStart();
    if (!cfg.isEnabled()) {
      log.info("Prometheus reporter is disabled");
      return;
    }
    httpServer = HTTPServer.builder()
      .port(cfg.getPort())
      .registry(apiMetrics.getRegistry())
      .buildAndStart();
    log.info(
      "Prometheus reporter started — /metrics available on port {}",
      cfg.getPort()
    );
  }

  @Override
  protected void doStop() throws Exception {
    if (httpServer != null) {
      httpServer.close();
    }
    super.doStop();
  }

  @Override
  public boolean canHandle(Reportable reportable) {
    if (!cfg.isEnabled()) return false;
    return switch (reportable) {
      case Metrics ignored -> true;
      case EndpointStatus ignored -> true;
      default -> false;
    };
  }

  @Override
  public void report(Reportable reportable) {
    if (!cfg.isEnabled()) return;
    try {
      switch (reportable) {
        case Metrics m -> apiMetrics.recordRequest(m);
        case EndpointStatus es -> apiMetrics.recordHealthCheck(es);
        default -> {}
      }
    } catch (Exception e) {
      log.warn("Unexpected error while reporting to Prometheus — skipping", e);
    }
  }
}
