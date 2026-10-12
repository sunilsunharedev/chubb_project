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
    assertThat(client.get().uri("/swagger-ui/index.html").retrieve().body(String.class))
        .contains("Swagger UI");
    JsonNode claim =
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
            .body(JsonNode.class);
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
