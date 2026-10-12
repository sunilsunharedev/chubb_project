package com.chubb.claims.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import com.chubb.claims.assignment.AssignmentService;
import com.chubb.claims.claim.*;
import com.chubb.claims.workflow.WorkflowService;
import jakarta.validation.*;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.*;

class ApiExceptionHandlerTest {
  private static final String PRIVATE =
      "claimant-secret SQL db=private password=secret broker=private";
  private final ClaimService claims = mock(ClaimService.class);
  private final ProbeController probe = new ProbeController();
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ClaimController(
                    claims, mock(AssignmentService.class), mock(WorkflowService.class)),
                probe)
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                .WRITE_DATES_AS_TIMESTAMPS)
                        .build()))
            .addFilters(new CorrelationFilter())
            .build();
  }

  static Stream<Arguments> unexpectedFailures() {
    return Stream.of(
        Arguments.of(new IllegalStateException(PRIVATE)),
        Arguments.of(new DataAccessResourceFailureException(PRIVATE)),
        Arguments.of(new UnexpectedRollbackException(PRIVATE)),
        Arguments.of(
            new DataIntegrityViolationException(PRIVATE, new SQLException(PRIVATE, "23502"))),
        Arguments.of(
            new DataIntegrityViolationException(PRIVATE, new SQLException(PRIVATE, "23503"))),
        Arguments.of(
            new DataIntegrityViolationException(PRIVATE, new SQLException(PRIVATE, "23514"))),
        Arguments.of(new DataIntegrityViolationException(PRIVATE)));
  }

  @ParameterizedTest
  @MethodSource("unexpectedFailures")
  void technicalFailuresReturnSafe500AndLogStackFramesWithoutSensitiveMessages(
      RuntimeException failure) throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    var appender =
        new ListAppender<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    try {
      when(claims.get(any())).thenThrow(failure);
      var result = getFailure(500, "INTERNAL_ERROR");
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("secret", "SQL", "private", "Exception", "java.", "broker");
      assertThat(result.getResponse().getContentAsString())
          .contains("An internal error occurred while processing the request.");
      var entry =
          appender.list.stream()
              .filter(e -> e.getThrowableProxy() != null)
              .findFirst()
              .orElseThrow();
      assertThat(entry.getFormattedMessage()).contains("negative-test");
      assertThat(entry.getMDCPropertyMap()).containsEntry("correlationId", "negative-test");
      var trace = ThrowableProxyUtil.asString(entry.getThrowableProxy());
      assertThat(trace)
          .contains(failure.getClass().getName(), "ApiExceptionHandlerTest")
          .doesNotContain("claimant-secret", "password=secret", "db=private");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    assertThat(org.slf4j.MDC.get("correlationId")).isNull();
  }

  @Test
  void uniqueDatabaseViolationReturnsSafe409ButDoesNotExposeSql() throws Exception {
    when(claims.get(any()))
        .thenThrow(
            new DataIntegrityViolationException(PRIVATE, new SQLException(PRIVATE, "23505")));
    getFailure(409, "DUPLICATE_RESOURCE");
  }

  @Test
  void springDuplicateKeyWithoutSqlCauseIsAConflict() throws Exception {
    when(claims.get(any())).thenThrow(new DuplicateKeyException(PRIVATE));
    getFailure(409, "DUPLICATE_RESOURCE");
  }

  @Test
  void infrastructureOptimisticLockUsesSameStaleCodeAsExpectedVersion() throws Exception {
    when(claims.get(any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(Claim.class, UUID.randomUUID()));
    getFailure(409, "STALE_CLAIM_VERSION");
  }

  @Test
  void constraintViolationMapsToFieldValidationWithoutRejectedValue() throws Exception {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var input = new ClaimDtos.Assignment(0L, " ", "Synthetic private officer");
      probe.failure =
          new ConstraintViolationException(PRIVATE, factory.getValidator().validate(input));
      mvc.perform(get("/test/failure"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.fieldErrors.officerId").exists())
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("Synthetic private officer"))));
    }
  }

  @Test
  void missingRequiredQueryParameterHasConsistent400() throws Exception {
    mvc.perform(get("/test/required"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.fieldErrors.required").exists());
  }

  @Test
  void malformedCorrelationHeaderIsReplacedWithSafeId() throws Exception {
    when(claims.get(any())).thenThrow(new ClaimNotFoundException());
    var result =
        mvc.perform(
                get("/api/claims/" + UUID.randomUUID()).header("X-Correlation-ID", "secret value!"))
            .andExpect(status().isNotFound())
            .andReturn();
    var correlation = result.getResponse().getHeader("X-Correlation-ID");
    assertThat(correlation).matches("[a-f0-9-]{36}");
    assertThat(result.getResponse().getContentAsString())
        .contains(correlation)
        .doesNotContain("secret value!");
  }

  private MvcResult getFailure(int status, String code) throws Exception {
    var result =
        mvc.perform(
                get("/api/claims/" + UUID.randomUUID()).header("X-Correlation-ID", "negative-test"))
            .andExpect(status().is(status))
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.status").value(status))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.timestamp").isString())
            .andExpect(jsonPath("$.correlationId").value("negative-test"))
            .andExpect(jsonPath("$.fieldErrors").isMap())
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain(PRIVATE);
    return result;
  }

  @RestController
  static class ProbeController {
    RuntimeException failure;

    @GetMapping("/test/failure")
    String fail() {
      throw failure;
    }

    @GetMapping("/test/required")
    String required(@RequestParam String required) {
      return required;
    }
  }
}
