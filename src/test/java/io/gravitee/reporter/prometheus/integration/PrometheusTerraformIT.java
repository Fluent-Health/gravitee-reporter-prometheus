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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class PrometheusTerraformIT {

  private static final Logger log = LoggerFactory.getLogger(
    PrometheusTerraformIT.class
  );
  private static final int PROMETHEUS_PORT = 9090;

  private static final Network NETWORK = Network.newNetwork();
  private static final String MONGO_URI =
    "mongodb://mongodb:27017/gravitee?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=5000";

  private static MongoDBContainer mongodb;
  private static GenericContainer<?> managementApi;
  private static GenericContainer<?> gateway;
  private static GenericContainer<?> httpbin;

  private static HttpClient http;
  private static String gatewayBase;
  private static String metricsBase;
  private static String mgmtBase;

  private static Path terraformDir;

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
      .withLogConsumer(FilteredLogConsumer.forContainer("management-api"))
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18083).forStatusCode(200)
      );

    httpbin = new GenericContainer<>("mccutchen/go-httpbin")
      .withNetwork(NETWORK)
      .withNetworkAliases("httpbin")
      .withExposedPorts(8080)
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
      .withLogConsumer(FilteredLogConsumer.forContainer("gateway"))
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200)
      );

    Startables.deepStart(gateway, httpbin).join();

    gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    metricsBase = "http://localhost:" + gateway.getMappedPort(PROMETHEUS_PORT);
    mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);

    http = HttpClient.newHttpClient();

    terraformDir = Files.createDirectories(Paths.get("target/terraform-it"));
  }

  @AfterAll
  static void stopInfrastructure() {
    Stream.of(gateway, httpbin, managementApi, mongodb)
      .filter(Objects::nonNull)
      .forEach(GenericContainer::stop);
    NETWORK.close();
  }

  @Test
  void shouldExposeHealthCheckMetricsWhenConfiguredViaTerraform()
    throws Exception {
    String tfConfig = """
      terraform {
        required_providers {
          apim = {
            source = "gravitee-io/apim"
            version = "~> 0.5.0"
          }
        }
      }

      provider "apim" {
        server_url = "%s/automation"
        username   = "admin"
        password   = "admin"
      }

      resource "apim_apiv4" "tf_health_check_api" {
        name            = "TF Health Check API"
        hrid            = "tf-health-check-api"
        version         = "1.0.0"
        lifecycle_state = "PUBLISHED"
        type            = "PROXY"
        description     = "API configured via Terraform with health checks"
        state           = "STARTED"

        listeners = [
          {
            http = {
              type  = "HTTP"
              paths = [{ path = "/tf-health" }]
              entrypoints = [{ type = "http-proxy" }]
            }
          }
        ]

        endpoint_groups = [
          {
            name = "default"
            type = "http-proxy"
            endpoints = [
              {
                name          = "main"
                type          = "http-proxy"
                configuration = jsonencode({
                  target = "http://httpbin:8080/status/200"
                })
                services = {
                  health_check = {
                    type                   = "http-health-check"
                    enabled                = true
                    override_configuration = true
                    configuration = jsonencode({
                      target   = "http://httpbin:8080/status/200"
                      schedule = "*/5 * * * * *"
                      method   = "GET"
                    })
                  }
                }
              }
            ]
          }
        ]

        plans = [
          {
            name     = "Default Plan"
            hrid     = "default-plan"
            mode     = "STANDARD"
            security = { type = "KEY_LESS" }
            status   = "PUBLISHED"
          }
        ]
      }
      """.formatted(mgmtBase);

    Files.writeString(terraformDir.resolve("main.tf"), tfConfig);

    runTerraform("init");
    runTerraform("apply", "-auto-approve");

    // Wait for API to be available on Gateway
    await("API to be available on gateway")
      .atMost(Duration.ofSeconds(60))
      .pollInterval(Duration.ofSeconds(2))
      .until(() -> {
        HttpResponse<Void> resp = http.send(
          HttpRequest.newBuilder()
            .uri(URI.create(gatewayBase + "/tf-health"))
            .build(),
          HttpResponse.BodyHandlers.discarding()
        );
        return resp.statusCode() == 200;
      });

    // Wait for health check metrics to appear
    await("health check metrics to appear")
      .atMost(Duration.ofSeconds(60))
      .pollInterval(Duration.ofSeconds(5))
      .until(() -> {
        String metrics = scrapeMetrics();
        return (
          metrics.contains(
            "gravitee_api_endpoint_up{api_name=\"TF Health Check API\",endpoint=\"main\"} 1.0"
          ) ||
          metrics.contains(
            "gravitee_api_endpoint_up{api_name=\"TF Health Check API\",endpoint=\"main\"} 0.0"
          )
        );
      });

    String metrics = scrapeMetrics();
    assertThat(metrics).contains(
      "gravitee_api_endpoint_up{api_name=\"TF Health Check API\",endpoint=\"main\"} 1.0"
    );
    assertThat(metrics).contains(
      "gravitee_api_health_checks_total{api_name=\"TF Health Check API\",endpoint=\"main\",result=\"success\"}"
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

  private void runTerraform(String... args)
    throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("terraform");
    command.addAll(List.of(args));

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(terraformDir.toFile());

    Path outFile = terraformDir.resolve("terraform-" + args[0] + ".log");
    pb.redirectOutput(outFile.toFile());
    pb.redirectError(outFile.toFile());

    Process p = pb.start();
    int exitCode = p.waitFor();

    String output = Files.readString(outFile);

    if (exitCode != 0) {
      log.error(
        "Terraform {} failed with exit code {}\nOutput:\n{}",
        args[0],
        exitCode,
        output
      );
      throw new RuntimeException("Terraform " + args[0] + " failed");
    }
    log.info("Terraform {} successful", args[0]);
  }
}
