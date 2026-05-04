# gravitee-reporter-prometheus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Gravitee APIM reporter plugin that exposes Prometheus metrics on a `/metrics` HTTP endpoint scraped by Grafana Alloy in-cluster.

**Architecture:** A single Maven/Java 21 JAR plugin modelled on `ivank/gravitee-reporter-gcloud`. `PrometheusReporter` receives Gravitee `Metrics` and `EndpointStatus` reportables and delegates to `ApiMetrics`, which holds the `PrometheusRegistry` and all metric instruments. `PrometheusReporter.doStart()` starts a Prometheus `HTTPServer` on the configured port; `doStop()` closes it.

**Tech Stack:** Java 21, Maven, `io.prometheus:prometheus-metrics-core:1.3.1`, `io.prometheus:prometheus-metrics-exporter-httpserver:1.3.1`, Spring (provided by gateway runtime), Testcontainers for integration tests.

---

## File Structure

**Production:**
- `pom.xml` — Maven build, dependencies, assembly plugin, integration-test profile
- `src/main/java/io/gravitee/reporter/prometheus/PrometheusReporter.java` — `AbstractService<Reporter>` + `Reporter`; starts/stops `HTTPServer`; delegates `report()` to `ApiMetrics`
- `src/main/java/io/gravitee/reporter/prometheus/PrometheusReporterConfiguration.java` — `@Value` bindings for `reporters.prometheus.enabled` and `reporters.prometheus.port`
- `src/main/java/io/gravitee/reporter/prometheus/metrics/ApiMetrics.java` — owns `PrometheusRegistry`; registers all 7 metric instruments; `recordRequest()` and `recordHealthCheck()` methods
- `src/main/java/io/gravitee/reporter/prometheus/spring/PrometheusReporterSpringConfiguration.java` — `@Configuration` wiring `PrometheusReporterConfiguration` and `ApiMetrics` beans
- `src/main/resources/plugin.properties`
- `src/main/resources/gravitee.json` — JSON Schema for management UI
- `src/main/resources/assembly/plugin-assembly.xml` — ZIP assembly descriptor

**Test:**
- `src/test/java/io/gravitee/reporter/prometheus/PrometheusTestSupport.java` — shared factory methods for synthetic `Metrics` and `EndpointStatus` fixtures
- `src/test/java/io/gravitee/reporter/prometheus/metrics/ApiMetricsTest.java` — unit tests for metric recording logic; uses real `PrometheusRegistry`, no mocks
- `src/test/java/io/gravitee/reporter/prometheus/PrometheusReporterTest.java` — unit tests for `canHandle()` / `report()` delegation; mocks `ApiMetrics`
- `src/test/java/io/gravitee/reporter/prometheus/integration/GraviteeManagementApi.java` — Retrofit2 interface for the Gravitee Management REST API v2
- `src/test/java/io/gravitee/reporter/prometheus/integration/ManagementApiHelper.java` — creates and deploys V4 HTTP proxy APIs via the Management API
- `src/test/java/io/gravitee/reporter/prometheus/integration/PrometheusReporterIT.java` — full-stack Testcontainers IT; starts gateway stack, sends traffic, scrapes `/metrics`, asserts

**CI/Infra:**
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- `catalog-info.yaml`
- `README.md` (written using `fh:developer-standards` skill)
- `.gitignore`

---

### Task 1: Maven project scaffold

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`

- [ ] **Step 1: Create `.gitignore`**

```
target/
*.class
*.jar
*.zip
.idea/
*.iml
*.iws
.DS_Store
local.properties
```

- [ ] **Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.gravitee</groupId>
        <artifactId>gravitee-parent</artifactId>
        <version>22.6.0</version>
    </parent>

    <groupId>io.gravitee.reporter</groupId>
    <artifactId>gravitee-reporter-prometheus</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Gravitee.io APIM - Reporter - Prometheus</name>
    <description>Reports API gateway metrics to a Prometheus /metrics endpoint</description>

    <properties>
        <gravitee-apim.version>4.9.0</gravitee-apim.version>
        <prometheus.version>1.3.1</prometheus.version>
        <maven.compiler.release>21</maven.compiler.release>
        <publish-folder-path>graviteeio-apim/plugins/reporters</publish-folder-path>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.gravitee.apim</groupId>
                <artifactId>gravitee-apim-bom</artifactId>
                <version>${gravitee-apim.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.gravitee.reporter</groupId>
                <artifactId>gravitee-reporter-api</artifactId>
                <version>1.35.1</version>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>1.20.4</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- ===== PROVIDED — supplied by the Gravitee gateway runtime ===== -->
        <dependency>
            <groupId>io.gravitee.gateway</groupId>
            <artifactId>gravitee-gateway-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.gravitee.reporter</groupId>
            <artifactId>gravitee-reporter-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.gravitee.common</groupId>
            <artifactId>gravitee-common</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.gravitee.node</groupId>
            <artifactId>gravitee-node-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-beans</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- ===== BUNDLED — shipped inside the plugin ZIP under lib/ ===== -->
        <dependency>
            <groupId>io.prometheus</groupId>
            <artifactId>prometheus-metrics-core</artifactId>
            <version>${prometheus.version}</version>
        </dependency>
        <dependency>
            <groupId>io.prometheus</groupId>
            <artifactId>prometheus-metrics-exporter-httpserver</artifactId>
            <version>${prometheus.version}</version>
        </dependency>

        <!-- ===== TEST ===== -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- ===== INTEGRATION TEST ===== -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <version>4.2.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.squareup.retrofit2</groupId>
            <artifactId>retrofit</artifactId>
            <version>2.11.0</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.squareup.retrofit2</groupId>
            <artifactId>converter-jackson</artifactId>
            <version>2.11.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
                <excludes><exclude>assembly/**</exclude></excludes>
            </resource>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
                <includes><include>assembly/**</include></includes>
            </resource>
        </resources>

        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration><release>21</release></configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                    <excludedGroups>integration</excludedGroups>
                    <argLine>
                        -Dnet.bytebuddy.experimental=true
                        --add-opens java.base/java.lang=ALL-UNNAMED
                        --add-opens java.base/java.util=ALL-UNNAMED
                    </argLine>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-dependencies</id>
                        <phase>prepare-package</phase>
                        <goals><goal>copy-dependencies</goal></goals>
                        <configuration>
                            <outputDirectory>${project.build.directory}/dependencies</outputDirectory>
                            <includeScope>runtime</includeScope>
                            <excludeScope>provided</excludeScope>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <configuration>
                    <appendAssemblyId>false</appendAssemblyId>
                    <descriptors>
                        <descriptor>src/main/resources/assembly/plugin-assembly.xml</descriptor>
                    </descriptors>
                </configuration>
                <executions>
                    <execution>
                        <id>make-plugin-assembly</id>
                        <phase>package</phase>
                        <goals><goal>single</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>integration-test</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <version>3.2.5</version>
                        <executions>
                            <execution>
                                <goals>
                                    <goal>integration-test</goal>
                                    <goal>verify</goal>
                                </goals>
                            </execution>
                        </executions>
                        <configuration>
                            <includes><include>**/*IT.java</include></includes>
                            <systemPropertyVariables>
                                <project.version>${project.version}</project.version>
                            </systemPropertyVariables>
                            <argLine>-Dnet.bytebuddy.experimental=true</argLine>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 3: Verify compilation**

Run: `mvn clean compile`
Expected: `BUILD SUCCESS` (no source files yet, but dependencies resolve and the project structure is valid)

- [ ] **Step 4: Commit**

```bash
git add pom.xml .gitignore
git commit -m "feat: add Maven project scaffold"
```

---

### Task 2: Plugin resources

**Files:**
- Create: `src/main/resources/plugin.properties`
- Create: `src/main/resources/gravitee.json`
- Create: `src/main/resources/assembly/plugin-assembly.xml`

- [ ] **Step 1: Create `src/main/resources/plugin.properties`**

```properties
id=prometheus
name=${project.name}
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.prometheus.PrometheusReporter
type=reporter
```

- [ ] **Step 2: Create `src/main/resources/gravitee.json`**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "Prometheus Reporter",
  "description": "Exposes Gravitee API gateway metrics on a Prometheus /metrics endpoint",
  "properties": {
    "enabled": {
      "type": "boolean",
      "default": true,
      "title": "Enabled",
      "description": "Activate the Prometheus reporter"
    },
    "port": {
      "type": "integer",
      "default": 9090,
      "title": "Port",
      "description": "Port on which the /metrics endpoint is served"
    }
  }
}
```

- [ ] **Step 3: Create `src/main/resources/assembly/plugin-assembly.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
              https://maven.apache.org/xsd/assembly-2.2.0.xsd">
    <id>plugin-assembly</id>
    <formats><format>zip</format></formats>
    <includeBaseDirectory>false</includeBaseDirectory>
    <files>
        <file>
            <source>${project.build.directory}/${project.build.finalName}.jar</source>
            <outputDirectory>/</outputDirectory>
        </file>
    </files>
    <fileSets>
        <fileSet>
            <directory>${project.build.directory}/dependencies</directory>
            <outputDirectory>lib</outputDirectory>
        </fileSet>
    </fileSets>
</assembly>
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/
git commit -m "feat: add plugin resources (plugin.properties, gravitee.json, assembly descriptor)"
```

---

### Task 3: PrometheusReporterConfiguration

**Files:**
- Create: `src/main/java/io/gravitee/reporter/prometheus/PrometheusReporterConfiguration.java`

- [ ] **Step 1: Create `PrometheusReporterConfiguration.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
 */
package io.gravitee.reporter.prometheus;

import org.springframework.beans.factory.annotation.Value;

public class PrometheusReporterConfiguration {

    @Value("${reporters.prometheus.enabled:true}")
    private boolean enabled;

    @Value("${reporters.prometheus.port:9090}")
    private int port;

    public boolean isEnabled() {
        return enabled;
    }

    public int getPort() {
        return port;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn clean compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/gravitee/reporter/prometheus/PrometheusReporterConfiguration.java
git commit -m "feat: add PrometheusReporterConfiguration"
```

---

### Task 4: ApiMetrics (TDD)

**Files:**
- Create: `src/test/java/io/gravitee/reporter/prometheus/PrometheusTestSupport.java`
- Create: `src/test/java/io/gravitee/reporter/prometheus/metrics/ApiMetricsTest.java`
- Create: `src/main/java/io/gravitee/reporter/prometheus/metrics/ApiMetrics.java`

- [ ] **Step 1: Create `PrometheusTestSupport.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
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
        EndpointStatus s = EndpointStatus
            .forEndpoint("api-123", "Test API", "https://backend.example.com/health")
            .on(System.currentTimeMillis())
            .build();
        s.setAvailable(available);
        s.setResponseTime(100);
        return s;
    }
}
```

- [ ] **Step 2: Create `ApiMetricsTest.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
 */
package io.gravitee.reporter.prometheus.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.metric.Metrics;
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

    // ===== recordRequest =====

    @Test
    void recordRequest_incrementsRequestsTotal() {
        apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
        String out = scrape();
        assertThat(out).contains(
            "gravitee_api_requests_total{api_name=\"Test API\",method=\"GET\",status=\"200\"} 1.0"
        );
    }

    @Test
    void recordRequest_multipleCallsAccumulate() {
        apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
        apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
        String out = scrape();
        assertThat(out).contains(
            "gravitee_api_requests_total{api_name=\"Test API\",method=\"GET\",status=\"200\"} 2.0"
        );
    }

    @Test
    void recordRequest_observesDuration() {
        apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
        String out = scrape();
        assertThat(out).contains("gravitee_api_request_duration_milliseconds_count{api_name=\"Test API\"} 1");
    }

    @Test
    void recordRequest_observesRequestAndResponseSizes() {
        apiMetrics.recordRequest(PrometheusTestSupport.metrics(200));
        String out = scrape();
        assertThat(out).contains("gravitee_api_request_size_bytes_count{api_name=\"Test API\"} 1");
        assertThat(out).contains("gravitee_api_response_size_bytes_count{api_name=\"Test API\"} 1");
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
            "gravitee_api_errors_total{api_name=\"Test API\",status=\"404\"} 1.0"
        );
    }

    @Test
    void recordRequest_incrementsErrorsForServerError() {
        apiMetrics.recordRequest(PrometheusTestSupport.metrics(500));
        String out = scrape();
        assertThat(out).contains(
            "gravitee_api_errors_total{api_name=\"Test API\",status=\"500\"} 1.0"
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
        Metrics m = PrometheusTestSupport.metrics(200);
        m.setApiName(null);
        apiMetrics.recordRequest(m);
        String out = scrape();
        assertThat(out).contains("api_name=\"unknown\"");
    }

    // ===== recordHealthCheck =====

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

    // ===== helper =====

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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=ApiMetricsTest`
Expected: compilation error — `ApiMetrics` does not exist yet

- [ ] **Step 4: Create `ApiMetrics.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
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
            .labelNames("api_name", "method", "status")
            .register(registry);

        requestDuration = Histogram.builder()
            .name("gravitee_api_request_duration_milliseconds")
            .help("API request duration in milliseconds")
            .labelNames("api_name")
            .classicUpperBounds(50, 100, 250, 500, 1000, 2500, 5000)
            .register(registry);

        errorsTotal = Counter.builder()
            .name("gravitee_api_errors_total")
            .help("Total number of API requests resulting in a 4xx or 5xx response")
            .labelNames("api_name", "status")
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
        String method = m.getHttpMethod() != null ? m.getHttpMethod().name() : "UNKNOWN";
        String status = String.valueOf(m.getStatus());

        requestsTotal.labelValues(apiName, method, status).inc();
        requestDuration.labelValues(apiName).observe(m.getGatewayResponseTimeMs());

        if (m.getStatus() >= 400) {
            errorsTotal.labelValues(apiName, status).inc();
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
        healthChecksTotal.labelValues(apiName, endpoint, es.isAvailable() ? "success" : "failure").inc();
    }

    public PrometheusRegistry getRegistry() {
        return registry;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=ApiMetricsTest`
Expected: all tests pass (`BUILD SUCCESS`)

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add ApiMetrics with counter/histogram/gauge registration and recording"
```

---

### Task 5: PrometheusReporter (TDD)

**Files:**
- Create: `src/test/java/io/gravitee/reporter/prometheus/PrometheusReporterTest.java`
- Create: `src/main/java/io/gravitee/reporter/prometheus/PrometheusReporter.java`

- [ ] **Step 1: Create `PrometheusReporterTest.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
 */
package io.gravitee.reporter.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.gravitee.node.api.monitor.Monitor;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.prometheus.metrics.ApiMetrics;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrometheusReporterTest {

    @Mock
    private PrometheusReporterConfiguration cfg;

    @Mock
    private ApiMetrics apiMetrics;

    private PrometheusReporter reporter;

    @BeforeEach
    void setUp() throws Exception {
        when(cfg.isEnabled()).thenReturn(true);
        when(cfg.getPort()).thenReturn(0); // port 0 = OS assigns a free port

        reporter = new PrometheusReporter();
        inject(reporter, "cfg", cfg);
        inject(reporter, "apiMetrics", apiMetrics);

        reporter.doStart();
    }

    // ===== canHandle =====

    @Test
    void metricsAreHandledWhenEnabled() {
        assertThat(reporter.canHandle(PrometheusTestSupport.metrics(200))).isTrue();
    }

    @Test
    void endpointStatusIsHandledWhenEnabled() {
        assertThat(reporter.canHandle(PrometheusTestSupport.endpointStatus(true))).isTrue();
    }

    @Test
    void logIsNotHandled() {
        assertThat(reporter.canHandle(Log.builder().build())).isFalse();
    }

    @Test
    void messageMetricsIsNotHandled() {
        assertThat(reporter.canHandle(MessageMetrics.builder().build())).isFalse();
    }

    @Test
    void monitorIsNotHandled() {
        assertThat(reporter.canHandle(mock(Monitor.class))).isFalse();
    }

    @Test
    void disabledReporterHandlesNothing() {
        when(cfg.isEnabled()).thenReturn(false);
        assertThat(reporter.canHandle(PrometheusTestSupport.metrics(200))).isFalse();
        assertThat(reporter.canHandle(PrometheusTestSupport.endpointStatus(true))).isFalse();
    }

    // ===== report =====

    @Test
    void metricsCallsRecordRequest() throws Exception {
        Metrics m = PrometheusTestSupport.metrics(200);
        reporter.report(m);
        verify(apiMetrics, times(1)).recordRequest(m);
    }

    @Test
    void endpointStatusCallsRecordHealthCheck() throws Exception {
        EndpointStatus es = PrometheusTestSupport.endpointStatus(true);
        reporter.report(es);
        verify(apiMetrics, times(1)).recordHealthCheck(es);
    }

    @Test
    void disabledReporterDoesNotDelegate() throws Exception {
        when(cfg.isEnabled()).thenReturn(false);
        reporter.report(PrometheusTestSupport.metrics(200));
        verify(apiMetrics, never()).recordRequest(any());
    }

    // ===== helpers =====

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=PrometheusReporterTest`
Expected: compilation error — `PrometheusReporter` does not exist yet

- [ ] **Step 3: Create `PrometheusReporter.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
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

public class PrometheusReporter extends AbstractService<Reporter> implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(PrometheusReporter.class);

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
        log.info("Prometheus reporter started — /metrics available on port {}", cfg.getPort());
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=PrometheusReporterTest`
Expected: all tests pass (`BUILD SUCCESS`)

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: add PrometheusReporter with canHandle/report delegation and HTTPServer lifecycle"
```

---

### Task 6: Spring configuration + full unit test suite

**Files:**
- Create: `src/main/java/io/gravitee/reporter/prometheus/spring/PrometheusReporterSpringConfiguration.java`

- [ ] **Step 1: Create `PrometheusReporterSpringConfiguration.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
 */
package io.gravitee.reporter.prometheus.spring;

import io.gravitee.reporter.prometheus.PrometheusReporterConfiguration;
import io.gravitee.reporter.prometheus.metrics.ApiMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrometheusReporterSpringConfiguration {

    @Bean
    public PrometheusReporterConfiguration prometheusReporterConfiguration() {
        return new PrometheusReporterConfiguration();
    }

    @Bean
    public ApiMetrics apiMetrics() {
        return new ApiMetrics(new PrometheusRegistry());
    }
}
```

- [ ] **Step 2: Run full unit test suite**

Run: `mvn clean verify`
Expected: all unit tests pass (`BUILD SUCCESS`); no integration tests run (excluded by `excludedGroups=integration`)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/gravitee/reporter/prometheus/spring/
git commit -m "feat: add Spring configuration wiring PrometheusReporterConfiguration and ApiMetrics"
```

---

### Task 7: CI/Release workflows + package verification

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  push:
    branches: ["**"]
  pull_request:

jobs:
  build:
    name: Build & unit tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
          cache: maven

      - name: Build and run unit tests
        run: mvn --batch-mode verify

  integration-test:
    name: Integration tests
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
          cache: maven

      - name: Build plugin ZIP and run integration tests
        run: mvn --batch-mode verify -Pintegration-test
```

- [ ] **Step 2: Create `.github/workflows/release.yml`**

```yaml
name: Release

on:
  release:
    types: [published]

permissions:
  contents: read

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    env:
      GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - name: Extract version
        id: version
        run: |
          TAG="${{ github.event.release.tag_name }}"
          VERSION="${TAG#v}"
          echo "version=${VERSION}" >> $GITHUB_OUTPUT
      - name: Set Maven version
        run: |
          mvn --batch-mode versions:set \
              -DnewVersion=${{ steps.version.outputs.version }} \
              -DgenerateBackupPoms=false
      - name: Run unit tests and build ZIP
        run: mvn --batch-mode verify
      - name: Upload ZIP to GitHub Release
        run: |
          gh release upload ${{ github.event.release.tag_name }} \
            target/gravitee-reporter-prometheus-${{ steps.version.outputs.version }}.zip \
            --clobber
```

- [ ] **Step 3: Verify the ZIP is produced**

Run: `mvn clean package -DskipTests`
Expected: `BUILD SUCCESS` and `target/gravitee-reporter-prometheus-1.0.0-SNAPSHOT.zip` exists

Verify: `ls -lh target/*.zip`
Expected: file exists, size > 0

- [ ] **Step 4: Commit**

```bash
git add .github/
git commit -m "feat: add CI and release GitHub Actions workflows"
```

---

### Task 8: Integration test

**Files:**
- Create: `src/test/java/io/gravitee/reporter/prometheus/integration/GraviteeManagementApi.java`
- Create: `src/test/java/io/gravitee/reporter/prometheus/integration/ManagementApiHelper.java`
- Create: `src/test/java/io/gravitee/reporter/prometheus/integration/PrometheusReporterIT.java`

- [ ] **Step 1: Create `GraviteeManagementApi.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
 */
package io.gravitee.reporter.prometheus.integration;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Path;

interface GraviteeManagementApi {
    @POST("apis")
    Call<JsonNode> createApi(@Body RequestBody body);

    @POST("apis/{id}/plans")
    Call<JsonNode> createPlan(@Path("id") String apiId, @Body RequestBody body);

    @POST("apis/{id}/plans/{planId}/_publish")
    Call<Void> publishPlan(@Path("id") String apiId, @Path("planId") String planId, @Body RequestBody body);

    @POST("apis/{id}/_start")
    Call<Void> startApi(@Path("id") String apiId, @Body RequestBody body);

    @POST("apis/{id}/deployments")
    Call<Void> deployApi(@Path("id") String apiId, @Body RequestBody body);
}
```

- [ ] **Step 2: Create `ManagementApiHelper.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
 */
package io.gravitee.reporter.prometheus.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

class ManagementApiHelper {

    private static final Logger log = LoggerFactory.getLogger(ManagementApiHelper.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ORG_ENV = "management/v2/organizations/DEFAULT/environments/DEFAULT/";
    private static final RequestBody EMPTY = RequestBody.create(JSON, "{}");

    private final GraviteeManagementApi api;

    ManagementApiHelper(String baseUrl) {
        String credentials = Base64.getEncoder().encodeToString(
            "admin:admin".getBytes(StandardCharsets.UTF_8)
        );
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Basic " + credentials)
                    .build()
            ))
            .build();

        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.api = new Retrofit.Builder()
            .baseUrl(base + ORG_ENV)
            .client(client)
            .addConverterFactory(JacksonConverterFactory.create())
            .build()
            .create(GraviteeManagementApi.class);
    }

    String createAndDeployApi(String name, String contextPath, String backendUrl) throws Exception {
        String apiBody = """
            {
              "name": "%s",
              "apiVersion": "1.0.0",
              "definitionVersion": "V4",
              "type": "PROXY",
              "description": "Integration test API for gravitee-reporter-prometheus",
              "listeners": [{
                "type": "HTTP",
                "paths": [{"path": "%s"}],
                "entrypoints": [{"type": "http-proxy"}]
              }],
              "endpointGroups": [{
                "name": "default",
                "type": "http-proxy",
                "endpoints": [{
                  "name": "main",
                  "type": "http-proxy",
                  "weight": 1,
                  "inheritConfiguration": false,
                  "configuration": {"target": "%s"}
                }]
              }]
            }
            """.formatted(name, contextPath, backendUrl);

        Response<JsonNode> createResp = api.createApi(body(apiBody)).execute();
        assertOk("createApi", createResp);
        String apiId = createResp.body().get("id").asText();
        log.info("Created API id={} name='{}' path={}", apiId, name, contextPath);

        String planBody = """
            {
              "name": "Default Plan",
              "definitionVersion": "V4",
              "security": {"type": "KEY_LESS"}
            }
            """;
        Response<JsonNode> planResp = api.createPlan(apiId, body(planBody)).execute();
        assertOk("createPlan", planResp);
        String planId = planResp.body().get("id").asText();

        assertOk("publishPlan", api.publishPlan(apiId, planId, EMPTY).execute());
        assertOk("startApi", api.startApi(apiId, EMPTY).execute());
        assertOk("deployApi", api.deployApi(apiId, EMPTY).execute());

        log.info("API '{}' deployed successfully (id={})", name, apiId);
        return apiId;
    }

    private static RequestBody body(String json) {
        return RequestBody.create(JSON, json);
    }

    private static void assertOk(String step, Response<?> resp) {
        if (!resp.isSuccessful()) {
            String err = "";
            try { err = resp.errorBody() != null ? resp.errorBody().string() : ""; } catch (Exception ignored) {}
            throw new IllegalStateException(
                "Management API step '%s' failed: HTTP %d — %s".formatted(step, resp.code(), err)
            );
        }
    }
}
```

- [ ] **Step 3: Create `PrometheusReporterIT.java`**

```java
/*
 * Copyright 2026 Fluent Health
 *
 * Licensed under the MIT License. See LICENSE in the project root for details.
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

/**
 * End-to-end integration test for the {@code gravitee-reporter-prometheus} plugin.
 *
 * <p>Starts a full Gravitee APIM 4.9 stack (MongoDB + Management API + Gateway) plus a
 * go-httpbin mock backend. Mounts the built plugin ZIP into the gateway. Creates two V4 HTTP
 * proxy APIs (one 200, one 500 backend). Sends traffic through the gateway, then scrapes
 * {@code /metrics} and asserts on counter values.
 *
 * <p>No external credentials are required — the Prometheus endpoint is local.
 *
 * <p>Run with: {@code mvn clean verify -Pintegration-test}
 */
@Tag("integration")
class PrometheusReporterIT {

    private static final Logger log = LoggerFactory.getLogger(PrometheusReporterIT.class);
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
        String pluginVersion = System.getProperty("project.version", "1.0.0-SNAPSHOT");
        Path pluginZip = Paths.get("target/gravitee-reporter-prometheus-" + pluginVersion + ".zip");
        assertThat(pluginZip)
            .as("Plugin ZIP not found at %s — run 'mvn package -DskipTests' first", pluginZip.toAbsolutePath())
            .exists();

        mongodb = new MongoDBContainer("mongo:7.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("mongodb");

        managementApi = new GenericContainer<>("graviteeio/apim-management-api:4.9.13")
            .withNetwork(NETWORK)
            .withNetworkAliases("management-api")
            .withExposedPorts(8083, 18083)
            .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
            .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
            .withEnv("gravitee_analytics_type", "none")
            .withEnv("gravitee_plugins_path_0", "/opt/graviteeio-management-api/plugins")
            .withEnv("gravitee_services_core_http_enabled", "true")
            .withEnv("gravitee_services_core_http_port", "18083")
            .withEnv("gravitee_services_core_http_host", "0.0.0.0")
            .withEnv("gravitee_services_core_http_authentication_type", "none")
            .dependsOn(mongodb)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.management-api")))
            .waitingFor(Wait.forHttp("/_node/health").forPort(18083).forStatusCode(200));

        httpbin = new GenericContainer<>("mccutchen/go-httpbin")
            .withNetwork(NETWORK)
            .withNetworkAliases("httpbin")
            .withExposedPorts(8080)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.httpbin")))
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
            .withEnv("gravitee_reporters_prometheus_port", String.valueOf(PROMETHEUS_PORT))
            .dependsOn(managementApi)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.gateway")))
            .waitingFor(Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200));

        Startables.deepStart(gateway, httpbin).join();

        gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
        metricsBase = "http://localhost:" + gateway.getMappedPort(PROMETHEUS_PORT);
        String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);

        http = HttpClient.newHttpClient();
        mgmtHelper = new ManagementApiHelper(mgmtBase);

        mgmtHelper.createAndDeployApi("Prometheus IT Success", "/prom-it-ok", "http://httpbin:8080/status/200");
        mgmtHelper.createAndDeployApi("Prometheus IT Error", "/prom-it-err", "http://httpbin:8080/status/500");

        await("gateway to serve success API")
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofSeconds(3))
            .until(() -> http.send(
                HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/prom-it-ok")).build(),
                HttpResponse.BodyHandlers.discarding()
            ).statusCode() != 404);

        await("gateway to serve error API")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(3))
            .until(() -> http.send(
                HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/prom-it-err")).build(),
                HttpResponse.BodyHandlers.discarding()
            ).statusCode() != 404);
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
            HttpRequest.newBuilder().uri(URI.create(metricsBase + "/metrics")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("gravitee_api_requests_total");
    }

    @Test
    void requestCounterIncrements() throws Exception {
        http.send(
            HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/prom-it-ok")).build(),
            HttpResponse.BodyHandlers.discarding()
        );

        await("requests_total counter to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> scrapeMetrics().contains(
                "gravitee_api_requests_total{api_name=\"Prometheus IT Success\""
            ));

        String metrics = scrapeMetrics();
        assertThat(metrics).contains(
            "gravitee_api_requests_total{api_name=\"Prometheus IT Success\",method=\"GET\",status=\"200\"}"
        );
    }

    @Test
    void errorCounterAppearsFor5xxResponse() throws Exception {
        http.send(
            HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/prom-it-err")).build(),
            HttpResponse.BodyHandlers.discarding()
        );

        await("errors_total counter to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> scrapeMetrics().contains(
                "gravitee_api_errors_total{api_name=\"Prometheus IT Error\""
            ));

        String metrics = scrapeMetrics();
        assertThat(metrics).contains("gravitee_api_errors_total{api_name=\"Prometheus IT Error\"");
    }

    @Test
    void durationHistogramIsPresent() throws Exception {
        http.send(
            HttpRequest.newBuilder().uri(URI.create(gatewayBase + "/prom-it-ok")).build(),
            HttpResponse.BodyHandlers.discarding()
        );

        await("duration histogram to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> scrapeMetrics().contains("gravitee_api_request_duration_milliseconds_bucket"));

        assertThat(scrapeMetrics()).contains("gravitee_api_request_duration_milliseconds_bucket");
    }

    private String scrapeMetrics() throws Exception {
        return http.send(
            HttpRequest.newBuilder().uri(URI.create(metricsBase + "/metrics")).build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }
}
```

- [ ] **Step 4: Build the plugin ZIP (required before IT can mount it)**

Run: `mvn clean package -DskipTests`
Expected: `BUILD SUCCESS`, `target/gravitee-reporter-prometheus-1.0.0-SNAPSHOT.zip` exists

- [ ] **Step 5: Run integration tests**

Run: `mvn verify -Pintegration-test` (Docker must be running)
Expected: `BUILD SUCCESS`, all IT tests pass

- [ ] **Step 6: Commit**

```bash
git add src/test/java/io/gravitee/reporter/prometheus/integration/
git commit -m "feat: add full-stack Testcontainers integration test"
```

---

### Task 9: catalog-info.yaml

**Files:**
- Create: `catalog-info.yaml`

- [ ] **Step 1: Create `catalog-info.yaml`**

```yaml
apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: gravitee-reporter-prometheus
  description: Gravitee APIM reporter plugin that exposes per-API metrics on a Prometheus /metrics endpoint
  annotations:
    github.com/project-slug: Fluent-Health/gravitee-reporter-prometheus
spec:
  type: library
  lifecycle: production
  owner: group:backend
  system: apim
```

- [ ] **Step 2: Commit**

```bash
git add catalog-info.yaml
git commit -m "feat: add Backstage catalog-info.yaml"
```

---

### Task 10: README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Invoke `fh:developer-standards` skill**

Use the `fh:developer-standards` skill to write the README. The README should cover:
- Purpose: Gravitee APIM reporter plugin exposing Prometheus `/metrics` endpoint
- Metrics reference table (all 7 metrics with type and labels)
- Quickstart: installation (copy ZIP to gateway `plugins-ext/`), configuration (`gravitee.yml` snippet)
- Configuration reference: `enabled`, `port`
- How to run tests: `mvn verify` (unit), `mvn verify -Pintegration-test` (IT, requires Docker)
- Release: tag a GitHub release → workflow builds and attaches ZIP automatically

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README"
```
