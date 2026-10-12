package com.chubb.claims.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;

@Configuration
public class OpenApiConfiguration {
  @Bean
  OpenApiCustomizer errorResponses() {
    return api -> {
      if (api.getComponents() == null) api.setComponents(new Components());
      var schema =
          new ObjectSchema()
              .addProperty("timestamp", new StringSchema().format("date-time"))
              .addProperty("status", new IntegerSchema())
              .addProperty("code", new StringSchema())
              .addProperty("message", new StringSchema())
              .addProperty("correlationId", new StringSchema())
              .addProperty("fieldErrors", new MapSchema().additionalProperties(new StringSchema()));
      api.getComponents().addSchemas("ApiError", schema);
      if (api.getPaths() == null) return;
      api.getPaths()
          .forEach(
              (path, item) -> {
                if (!path.startsWith("/api/")) return;
                item.readOperations()
                    .forEach(
                        operation -> {
                          add(operation, "400", "Invalid request or validation failure");
                          add(
                              operation,
                              "500",
                              "Unexpected technical failure; safe generic message");
                          if (path.contains("{id}"))
                            add(operation, "404", "Claim or information request not found");
                        });
                if (item.getPost() != null)
                  add(
                      item.getPost(),
                      "409",
                      "Invalid claim state, stale version or duplicate resource");
              });
      reviewerDocumentation(api);
    };
  }

  private void reviewerDocumentation(io.swagger.v3.oas.models.OpenAPI api) {
    var create = api.getPaths().get("/api/claims").getPost();
    create
        .summary("Create a motor or property claim")
        .description(
            "Send application/json. claimType: MOTOR or PROPERTY. market: AU, NZ, SG, HK, MY or TH."
                + " incidentDate uses yyyy-MM-dd and cannot be in the future (UTC)."
                + " estimatedLiability is nonnegative USD, with at most 17 integer digits and 2"
                + " decimal places. Copy id (UUID), claimNumber and version from the 201 response."
                + " New status is SUBMITTED; use the latest version as expectedVersion for"
                + " lifecycle commands.");
    create
        .getRequestBody()
        .getContent()
        .get("application/json")
        .example(
            java.util.Map.of(
                "claimType",
                "MOTOR",
                "market",
                "SG",
                "claimantName",
                "Demo claimant",
                "incidentDescription",
                "Collision at junction",
                "incidentDate",
                "2026-10-01",
                "estimatedLiability",
                new java.math.BigDecimal("1000.00")));
    var response = create.getResponses().remove("200");
    if (response != null) {
      response.description(
          "Created claim detail including id, claimNumber, status and version. Location points to"
              + " its UUID URL.");
      var media = response.getContent().values().iterator().next();
      response.setContent(new Content().addMediaType("application/json", media));
      create.getResponses().addApiResponse("201", response);
    }
    var get = api.getPaths().get("/api/claims/{id}").getGet();
    get.summary("Retrieve a claim by UUID")
        .description(
            "Paste the id returned by POST /api/claims; this is the UUID, not the claimNumber.");
    api.getPaths()
        .forEach(
            (path, item) ->
                item.readOperations()
                    .forEach(
                        operation -> {
                          if (operation.getParameters() == null) return;
                          operation.getParameters().stream()
                              .filter(p -> "id".equals(p.getName()))
                              .forEach(
                                  p ->
                                      p.description("UUID id returned when the claim was created")
                                          .example("123e4567-e89b-12d3-a456-426614174000"));
                        }));
    var byNumber = api.getPaths().get("/api/claims/by-number/{claimNumber}").getGet();
    byNumber
        .summary("Retrieve a claim by claim number")
        .description(
            "Exact, case-sensitive lookup of the claimNumber returned on creation. Returns full"
                + " detail.");
    byNumber
        .getParameters()
        .get(0)
        .description("Copy claimNumber from the create response")
        .example("CLM-123e4567e89b12d3a456426614174000");
    add(byNumber, "404", "Claim number not found");
    var list = api.getPaths().get("/api/claims").getGet();
    list.summary("List and filter claims")
        .description(
            "All supplied filters are combined with AND. Leave optional fields unset to list all"
                + " claims. Do not combine unassigned=true with assignedOfficerId unless an empty"
                + " result is intended. Results are ordered by reportedAt then id.");
    var descriptions =
        java.util.Map.of(
            "status", "Optional lifecycle status; for incoming claims select SUBMITTED",
            "assignedOfficerId",
                "Optional exact officer ID (1–100 characters); omit for all officers",
            "unassigned", "true returns only claims with no assigned officer; default false",
            "page", "Zero-based page index; minimum 0, default 0",
            "size", "Items per page, 1–100; default 20");
    list.getParameters().forEach(p -> p.description(descriptions.get(p.getName())));
  }

  private void add(
      io.swagger.v3.oas.models.Operation operation, String status, String description) {
    operation
        .getResponses()
        .putIfAbsent(
            status,
            new ApiResponse()
                .description(description)
                .content(
                    new Content()
                        .addMediaType(
                            MediaType.APPLICATION_JSON_VALUE,
                            new io.swagger.v3.oas.models.media.MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
  }
}
