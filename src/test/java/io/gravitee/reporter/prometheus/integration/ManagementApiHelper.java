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

  private static final Logger log = LoggerFactory.getLogger(
    ManagementApiHelper.class
  );
  private static final MediaType JSON = MediaType.get(
    "application/json; charset=utf-8"
  );
  private static final String ORG_ENV =
    "management/v2/organizations/DEFAULT/environments/DEFAULT/";
  private static final RequestBody EMPTY = RequestBody.create(JSON, "{}");

  private final GraviteeManagementApi api;

  ManagementApiHelper(String baseUrl) {
    String credentials = Base64.getEncoder().encodeToString(
      "admin:admin".getBytes(StandardCharsets.UTF_8)
    );
    OkHttpClient client = new OkHttpClient.Builder()
      .addInterceptor(chain ->
        chain.proceed(
          chain
            .request()
            .newBuilder()
            .header("Authorization", "Basic " + credentials)
            .build()
        )
      )
      .build();

    String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    this.api = new Retrofit.Builder()
      .baseUrl(base + ORG_ENV)
      .client(client)
      .addConverterFactory(JacksonConverterFactory.create())
      .build()
      .create(GraviteeManagementApi.class);
  }

  String createAndDeployApi(String name, String contextPath, String backendUrl)
    throws Exception {
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
    Response<JsonNode> planResp = api
      .createPlan(apiId, body(planBody))
      .execute();
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
      try {
        err = resp.errorBody() != null ? resp.errorBody().string() : "";
      } catch (Exception ignored) {}
      throw new IllegalStateException(
        "Management API step '%s' failed: HTTP %d — %s".formatted(
          step,
          resp.code(),
          err
        )
      );
    }
  }
}
