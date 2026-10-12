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
    };
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
