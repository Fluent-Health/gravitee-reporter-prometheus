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
package io.gravitee.reporter.prometheus.integration;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.OutputFrame;

/**
 * Forwards only relevant container log lines to SLF4J, dropping Spring/Jetty/Mongo
 * bootstrap noise plus every "Install plugin: …" line for plugins we don't own.
 *
 * <p>What survives the filter:
 * <ul>
 *   <li>Our plugin's own lifecycle ({@code gravitee-reporter-prometheus},
 *       {@code PrometheusReporter})</li>
 *   <li>{@code ApiManagerImpl} deploy/undeploy lines (confirms the gateway picked
 *       up the test API)</li>
 *   <li>Real ERROR-level lines from the container, minus a small explicit
 *       safe-list of known benign boot-time messages</li>
 * </ul>
 *
 * <p>If a failure mode ever needs richer container output, swap a single container's
 * consumer back to {@link org.testcontainers.containers.output.Slf4jLogConsumer}.
 */
final class FilteredLogConsumer {

  /** Substrings whose presence forwards the line at INFO. Intentionally narrow. */
  private static final String[] INFO_ALLOWLIST = {
    "gravitee-reporter-prometheus",
    "PrometheusReporter",
    "ApiManagerImpl",
  };

  /**
   * Container log lines that look like errors but aren't. Each pattern names why
   * it is benign — a future real failure matching the same shape will still surface
   * if you remove the matching entry.
   */
  private static final Pattern BENIGN_ERROR = Pattern.compile(
    String.join(
      "|",
      // Gravitee gateway has Groovy-policy classpath probes for am-* classes
      // that aren't installed; they log at WARN with ClassNotFoundException.
      "SecuredResolver.*ClassNotFoundException",
      // K8s/probe boot complaints — harmless when running outside K8s.
      "KubernetesConnectivity",
      "KubernetesConfig",
      "InitialApiManager",
      // JDK 21 restricted-method warning emitted by mgmt-api's native deps.
      "restricted method in java.lang.foreign",
      "java.lang.foreign.Linker",
      "--enable-native-access=ALL-UNNAMED"
    )
  );

  /**
   * Lines that pass an explicit drop-list even if they otherwise look like errors.
   * httpbin emits all its access logs on STDERR with {@code level=INFO}; the 2xx
   * responses are not failures.
   */
  private static final Pattern STDERR_NOT_ERROR = Pattern.compile(
    "level=INFO|level=WARN"
  );

  /** Strips a leading "HH:mm:ss[.SSS] [thread] LEVEL" prefix the container itself added. */
  private static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
    "^\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3})?\\s+(?:\\[[^\\]]+]\\s+)?(?:[A-Z]{4,5}\\s+)?"
  );

  private FilteredLogConsumer() {}

  static Consumer<OutputFrame> forContainer(String containerName) {
    Logger logger = LoggerFactory.getLogger("tc." + containerName);
    return frame -> {
      String raw = frame.getUtf8String();
      if (raw == null || raw.isBlank()) return;

      for (String line : raw.split("\\R")) {
        if (line.isBlank()) continue;
        String cleaned = TIMESTAMP_PREFIX.matcher(line).replaceFirst("");

        boolean isError =
          (frame.getType() == OutputFrame.OutputType.STDERR &&
            !STDERR_NOT_ERROR.matcher(cleaned).find()) ||
          cleaned.contains("ERROR") ||
          cleaned.contains("Exception");

        if (isError && !BENIGN_ERROR.matcher(cleaned).find()) {
          logger.error("{}", cleaned);
          continue;
        }

        for (String token : INFO_ALLOWLIST) {
          if (cleaned.contains(token)) {
            logger.info("{}", cleaned);
            break;
          }
        }
      }
    };
  }
}
