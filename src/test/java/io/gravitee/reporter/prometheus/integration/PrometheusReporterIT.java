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
package io.gravitee.reporter.prometheus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class PrometheusReporterIT {

  private static final Logger log = LoggerFactory.getLogger(
    PrometheusReporterIT.class
  );
  private static final int PROMETHEUS_PORT = 9090;

  private static final Network NETWORK = Network.newNetwork();
  private static final String MONGO_URI =
    "mongodb://mongodb:27017/gravitee?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=5000";

  private static MongoDBContainer mongodb;
  private static GenericContainer<?> managementApi;
  private static GenericContainer<?> gateway;
  private static GenericContainer<?> httpbin;

  private static ManagementApiHelper mgmtHelper;
  private static HttpClient http;
  private static String gatewayBase;
  private static String metricsBase;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    String pluginVersion = System.getProperty(
      "project.version",
      "1.0.0-SNAPSHOT"
    );
    Path pluginZip = Paths.get(
      "target/gravitee-reporter-prometheus-" + pluginVersion + ".zip"
    );
    assertThat(pluginZip)
      .as(
        "Plugin ZIP not found at %s — run 'mvn package -DskipTests' first",
        pluginZip.toAbsolutePath()
      )
      .exists();

    mongodb = new MongoDBContainer("mongo:7.0")
      .withNetwork(NETWORK)
      .withNetworkAliases("mongodb");

    managementApi = new GenericContainer<>(
      "graviteeio/apim-management-api:4.9.13"
    )
      .withNetwork(NETWORK)
      .withNetworkAliases("management-api")
      .withExposedPorts(8083, 18083)
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_analytics_type", "none")
      .withEnv(
        "gravitee_plugins_path_0",
        "/opt/graviteeio-management-api/plugins"
      )
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18083")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      .dependsOn(mongodb)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.management-api"))
      )
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18083).forStatusCode(200)
      );

    httpbin = new GenericContainer<>("mccutchen/go-httpbin")
      .withNetwork(NETWORK)
      .withNetworkAliases("httpbin")
      .withExposedPorts(8080)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.httpbin"))
      )
      .waitingFor(Wait.forHttp("/get").forPort(8080).forStatusCode(200));

    gateway = new GenericContainer<>("graviteeio/apim-gateway:4.9.13")
      .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
      .withNetwork(NETWORK)
      .withNetworkAliases("gateway")
      .withExposedPorts(8082, 18082, PROMETHEUS_PORT)
      .withCopyFileToContainer(
        MountableFile.forHostPath(pluginZip.toAbsolutePath().toString()),
        "/opt/graviteeio-gateway/plugins-ext/gravitee-reporter-prometheus.zip"
      )
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_ratelimit_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_plugins_path_0", "/opt/graviteeio-gateway/plugins")
      .withEnv("gravitee_plugins_path_1", "/opt/graviteeio-gateway/plugins-ext")
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18082")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      .withEnv("gravitee_reporters_prometheus_enabled", "true")
      .withEnv(
        "gravitee_reporters_prometheus_port",
        String.valueOf(PROMETHEUS_PORT)
      )
      .dependsOn(managementApi)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.gateway"))
      )
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200)
      );

    Startables.deepStart(gateway, httpbin).join();

    gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    metricsBase = "http://localhost:" + gateway.getMappedPort(PROMETHEUS_PORT);
    String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);

    http = HttpClient.newHttpClient();
    mgmtHelper = new ManagementApiHelper(mgmtBase);

    mgmtHelper.createAndDeployApi(
      "Prometheus IT Success",
      "/prom-it-ok",
      "http://httpbin:8080/status/200"
    );
    mgmtHelper.createAndDeployApi(
      "Prometheus IT Error",
      "/prom-it-err",
      "http://httpbin:8080/status/500"
    );

    await("gateway to serve success API")
      .atMost(Duration.ofSeconds(90))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/prom-it-ok"))
                .build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );

    await("gateway to serve error API")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/prom-it-err"))
                .build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );
  }

  @AfterAll
  static void stopInfrastructure() {
    Stream.of(gateway, httpbin, managementApi, mongodb)
      .filter(Objects::nonNull)
      .forEach(GenericContainer::stop);
    NETWORK.close();
  }

  @Test
  void metricsEndpointIsReachable() throws Exception {
    HttpResponse<String> response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(metricsBase + "/metrics"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("gravitee_api_requests_total");
  }

  @Test
  void requestCounterIncrements() throws Exception {
    http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/prom-it-ok"))
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );

    await("requests_total counter to appear")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        scrapeMetrics().contains(
          "gravitee_api_requests_total{api_name=\"Prometheus IT Success\""
        )
      );

    String metrics = scrapeMetrics();
    assertThat(metrics).contains(
      "gravitee_api_requests_total{api_name=\"Prometheus IT Success\",method=\"GET\",status=\"200\"}"
    );
  }

  @Test
  void errorCounterAppearsFor5xxResponse() throws Exception {
    http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/prom-it-err"))
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );

    await("errors_total counter to appear")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        scrapeMetrics().contains(
          "gravitee_api_errors_total{api_name=\"Prometheus IT Error\""
        )
      );

    String metrics = scrapeMetrics();
    assertThat(metrics).contains(
      "gravitee_api_errors_total{api_name=\"Prometheus IT Error\""
    );
  }

  @Test
  void durationHistogramIsPresent() throws Exception {
    http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/prom-it-ok"))
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );

    await("duration histogram to appear")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        scrapeMetrics().contains(
          "gravitee_api_request_duration_milliseconds_bucket"
        )
      );

    assertThat(scrapeMetrics()).contains(
      "gravitee_api_request_duration_milliseconds_bucket"
    );
  }

  private String scrapeMetrics() throws Exception {
    return http
      .send(
        HttpRequest.newBuilder()
          .uri(URI.create(metricsBase + "/metrics"))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      .body();
  }
}
