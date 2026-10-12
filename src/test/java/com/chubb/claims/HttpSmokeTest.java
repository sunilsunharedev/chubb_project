package com.chubb.claims;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpSmokeTest {
  @LocalServerPort int port;

  @Test
  void runningServerExposesHealthOpenApiAndClaims() {
    RestClient client = RestClient.create("http://localhost:" + port);
    assertThat(
            client
                .get()
                .uri("/actuator/health")
                .retrieve()
                .body(JsonNode.class)
                .get("status")
                .asText())
        .isEqualTo("UP");
    JsonNode spec = client.get().uri("/v3/api-docs").retrieve().body(JsonNode.class);
    assertThat(spec.get("paths").has("/api/claims/{id}/settlement")).isTrue();
    assertThat(
            spec.get("paths")
                .get("/api/claims/{id}/settlement")
                .get("post")
                .get("responses")
                .has("409"))
        .isTrue();
    assertThat(spec.get("components").get("schemas").has("ApiError")).isTrue();
    var createSpec = spec.get("paths").get("/api/claims").get("post");
    assertThat(createSpec.get("responses").has("201")).isTrue();
    assertThat(createSpec.get("responses").has("200")).isFalse();
    assertThat(
            createSpec
                .get("requestBody")
                .get("content")
                .get("application/json")
                .get("example")
                .get("market")
                .asText())
        .isEqualTo("SG");
    assertThat(
            spec.get("paths")
                .get("/api/claims/by-number/{claimNumber}")
                .get("get")
                .get("responses")
                .has("404"))
        .isTrue();
    assertThat(client.get().uri("/swagger-ui/index.html").retrieve().body(String.class))
        .contains("Swagger UI");
    var createdResponse =
        client
            .post()
            .uri("/api/claims")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(
                java.util.Map.of(
                    "claimType",
                    "MOTOR",
                    "market",
                    "SG",
                    "claimantName",
                    "Test",
                    "incidentDescription",
                    "Collision",
                    "incidentDate",
                    java.time.LocalDate.now().minusDays(1).toString(),
                    "estimatedLiability",
                    100))
            .retrieve()
            .toEntity(JsonNode.class);
    assertThat(createdResponse.getStatusCode().value()).isEqualTo(201);
    JsonNode claim = createdResponse.getBody();
    String claimPath = "/api/claims/" + claim.get("id").asText();
    assertThat(createdResponse.getHeaders().getLocation().toString()).isEqualTo(claimPath);
    java.util.UUID.fromString(claim.get("id").asText());
    JsonNode detail = client.get().uri(claimPath).retrieve().body(JsonNode.class);
    assertThat(detail.get("claimantName").asText()).isEqualTo("Test");
    assertThat(detail.get("claimType").asText()).isEqualTo("MOTOR");
    assertThat(detail.get("market").asText()).isEqualTo("SG");
    assertThat(detail.get("estimatedLiability").decimalValue()).isEqualByComparingTo("100");
    assertThat(detail.get("id")).isEqualTo(claim.get("id"));
    JsonNode numbered =
        client
            .get()
            .uri("/api/claims/by-number/" + claim.get("claimNumber").asText())
            .retrieve()
            .body(JsonNode.class);
    assertThat(numbered).isEqualTo(detail);
    JsonNode history = client.get().uri(claimPath + "/history").retrieve().body(JsonNode.class);
    assertThat(history.size()).isEqualTo(1);
    assertThat(history.get(0).get("operation").asText()).isEqualTo("ClaimCreated");
    JsonNode page =
        client
            .get()
            .uri("/api/claims?status=SUBMITTED&unassigned=true&page=0&size=100")
            .retrieve()
            .body(JsonNode.class);
    assertThat(page.get("content").findValuesAsText("id")).contains(claim.get("id").asText());
    assertThat(claim.get("status").asText()).isEqualTo("SUBMITTED");
    JsonNode status =
        client
            .get()
            .uri("/api/claims/" + claim.get("id").asText() + "/status")
            .retrieve()
            .body(JsonNode.class);
    assertThat(status.get("version").asLong()).isZero();
  }

  @Test
  void runningServerReturnsRepresentativeNegativeErrors() {
    RestClient client = RestClient.create("http://localhost:" + port);
    expectError(
        client
            .post()
            .uri("/api/claims")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body("{}"),
        400,
        "VALIDATION_ERROR");
    expectError(client.get().uri("/api/claims/" + java.util.UUID.randomUUID()), 404, "NOT_FOUND");
    expectError(client.get().uri("/api/claims/by-number/CLM-unknown"), 404, "NOT_FOUND");
    var body =
        java.util.Map.of(
            "claimType",
            "MOTOR",
            "market",
            "SG",
            "claimantName",
            "Synthetic",
            "incidentDescription",
            "Incident",
            "incidentDate",
            java.time.LocalDate.now().minusDays(1).toString(),
            "estimatedLiability",
            10);
    var created =
        client
            .post()
            .uri("/api/claims")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
    String path = "/api/claims/" + created.get("id").asText();
    expectError(
        client
            .post()
            .uri(path + "/settlement")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(java.util.Map.of("expectedVersion", 0)),
        409,
        "CLAIM_CONFLICT");
    client
        .post()
        .uri(path + "/assignment")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            java.util.Map.of(
                "expectedVersion", 0, "officerId", "officer", "officerName", "Officer"))
        .retrieve()
        .toBodilessEntity();
    expectError(
        client
            .post()
            .uri(path + "/review")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(java.util.Map.of("expectedVersion", 0)),
        409,
        "STALE_CLAIM_VERSION");
  }

  private void expectError(RestClient.RequestHeadersSpec<?> request, int status, String code) {
    request
        .header("X-Correlation-ID", "http-negative")
        .exchange(
            (sent, response) -> {
              assertThat(response.getStatusCode().value()).isEqualTo(status);
              JsonNode error = response.bodyTo(JsonNode.class);
              assertThat(error.get("code").asText()).isEqualTo(code);
              assertThat(error.get("correlationId").asText()).isEqualTo("http-negative");
              return null;
            });
  }
}
