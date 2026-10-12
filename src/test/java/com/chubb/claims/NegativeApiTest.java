package com.chubb.claims;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.chubb.claims.assignment.AssignmentService;
import com.chubb.claims.claim.*;
import com.chubb.claims.event.OutboxRepository;
import com.chubb.claims.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NegativeApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ClaimService claims;
  @Autowired AssignmentService assignment;
  @Autowired WorkflowService workflow;
  @Autowired OutboxRepository outbox;

  private Map<String, Object> input() {
    return new LinkedHashMap<>(
        Map.of(
            "claimType",
            "MOTOR",
            "market",
            "SG",
            "claimantName",
            "Synthetic claimant",
            "incidentDescription",
            "Synthetic incident",
            "incidentDate",
            LocalDate.now().minusDays(1).toString(),
            "estimatedLiability",
            100));
  }

  static Stream<Arguments> invalidFields() {
    return Stream.of(
        Arguments.of("claimantName", " "),
        Arguments.of("claimantName", "x".repeat(201)),
        Arguments.of("incidentDescription", ""),
        Arguments.of("incidentDescription", "x".repeat(4001)),
        Arguments.of("estimatedLiability", new BigDecimal("-0.01")),
        Arguments.of("estimatedLiability", new BigDecimal("10.001")),
        Arguments.of("estimatedLiability", new BigDecimal("100000000000000000.00")),
        Arguments.of("incidentDate", LocalDate.now().plusDays(1).toString()));
  }

  @ParameterizedTest
  @MethodSource("invalidFields")
  void invalidCreationFieldsAreRejectedWithoutWrites(String field, Object value) throws Exception {
    var body = input();
    body.put(field, value);
    var response =
        mvc.perform(
            post("/api/claims")
                .contentType("application/json")
                .content(json.writeValueAsString(body)));
    assertError(response, 400, "VALIDATION_ERROR")
        .andExpect(jsonPath("$.fieldErrors." + field).exists());
    assertThat(outbox.count()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "claimType",
        "market",
        "claimantName",
        "incidentDescription",
        "incidentDate",
        "estimatedLiability"
      })
  void everyRequiredCreationFieldIsValidated(String field) throws Exception {
    var body = input();
    body.remove(field);
    assertError(
            mvc.perform(
                post("/api/claims")
                    .contentType("application/json")
                    .content(json.writeValueAsString(body))),
            400,
            "VALIDATION_ERROR")
        .andExpect(jsonPath("$.fieldErrors." + field).exists());
  }

  static Stream<Arguments> malformedFields() {
    return Stream.of(
        Arguments.of("claimType", "BOAT"),
        Arguments.of("market", "INVALID"),
        Arguments.of("incidentDate", "yesterday"),
        Arguments.of("incidentDate", "2026-02-30"),
        Arguments.of("estimatedLiability", "not-a-number"),
        Arguments.of("claimantName", Map.of("secret", "never-echo")));
  }

  @ParameterizedTest
  @MethodSource("malformedFields")
  void malformedTypedFieldsDoNotLeakRejectedValues(String field, Object value) throws Exception {
    var body = input();
    body.put(field, value);
    var result =
        assertError(
                mvc.perform(
                    post("/api/claims")
                        .contentType("application/json")
                        .content(json.writeValueAsString(body))),
                400,
                "INVALID_REQUEST")
            .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("never-echo", "Synthetic claimant", "not-a-number", "java.", "Exception");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{", "{\"claimType\":", "[]"})
  void missingOrMalformedBodyReturnsJson400(String body) throws Exception {
    assertError(
        mvc.perform(post("/api/claims").contentType("application/json").content(body)),
        400,
        "INVALID_REQUEST");
  }

  @ParameterizedTest
  @CsvSource({
    "page,-1",
    "page,nope",
    "size,0",
    "size,-1",
    "size,101",
    "status,UNKNOWN",
    "unassigned,nope",
    "assignedOfficerId,' '"
  })
  void invalidQueriesReturnUseful400(String name, String value) throws Exception {
    var result =
        assertError(mvc.perform(get("/api/claims").param(name, value)), 400, null).andReturn();
    assertThat(
            json.readTree(result.getResponse().getContentAsString()).get("fieldErrors").has(name))
        .isTrue();
  }

  @Test
  void oversizedOfficerFilterIsRejected() throws Exception {
    assertError(
        mvc.perform(get("/api/claims").param("assignedOfficerId", "")), 400, "VALIDATION_ERROR");
    assertError(
        mvc.perform(get("/api/claims").param("assignedOfficerId", "x".repeat(101))),
        400,
        "VALIDATION_ERROR");
  }

  @Test
  void uuidAndMediaErrorsAndMethodsKeepConsistentBody() throws Exception {
    assertError(mvc.perform(get("/api/claims/not-a-uuid")), 400, "INVALID_REQUEST");
    assertError(
        mvc.perform(post("/api/claims").contentType("text/plain").content("private-body")),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    assertError(
        mvc.perform(post("/api/claims").contentType("application/xml").content("<claim/>")),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    assertError(mvc.perform(get("/api/claims").accept("application/xml")), 406, "NOT_ACCEPTABLE");
    assertError(mvc.perform(delete("/api/claims/" + UUID.randomUUID())), 405, "METHOD_NOT_ALLOWED")
        .andExpect(header().exists("Allow"));
    assertError(mvc.perform(get("/api/nonexistent-resource")), 404, "NOT_FOUND");
  }

  @Test
  void missingClaimQueriesAndAssignmentReturn404() throws Exception {
    UUID id = UUID.randomUUID();
    for (String suffix : List.of("", "/status", "/history"))
      assertError(mvc.perform(get("/api/claims/" + id + suffix)), 404, "NOT_FOUND");
    assertError(
        command(
            id,
            "/assignment",
            Map.of("expectedVersion", 0, "officerId", "officer", "officerName", "Synthetic")),
        404,
        "NOT_FOUND");
  }

  static Stream<Arguments> invalidCommands() {
    return Stream.of(
        Arguments.of(
            "/assignment",
            Map.of("expectedVersion", 0, "officerId", " ", "officerName", "Officer"),
            "officerId"),
        Arguments.of(
            "/assignment",
            Map.of("expectedVersion", 0, "officerId", "officer", "officerName", " "),
            "officerName"),
        Arguments.of(
            "/assignment",
            Map.of("expectedVersion", 0, "officerId", "x".repeat(101), "officerName", "Officer"),
            "officerId"),
        Arguments.of(
            "/assignment",
            Map.of("expectedVersion", 0, "officerId", "officer", "officerName", "x".repeat(201)),
            "officerName"),
        Arguments.of("/rejection", Map.of("expectedVersion", 0, "reason", " "), "reason"),
        Arguments.of(
            "/assessment",
            Map.of(
                "expectedVersion",
                0,
                "estimatedLiability",
                10,
                "approvedSettlementAmount",
                -1,
                "reason",
                "valid"),
            "approvedSettlementAmount"),
        Arguments.of(
            "/assessment",
            Map.of(
                "expectedVersion",
                0,
                "estimatedLiability",
                10,
                "approvedSettlementAmount",
                10,
                "reason",
                " "),
            "reason"),
        Arguments.of(
            "/rejection", Map.of("expectedVersion", 0, "reason", "x".repeat(2001)), "reason"),
        Arguments.of(
            "/information-requests", Map.of("expectedVersion", 0, "question", " "), "question"),
        Arguments.of(
            "/information-requests",
            Map.of("expectedVersion", 0, "question", "x".repeat(256)),
            "question"),
        Arguments.of(
            "/information-requests/" + UUID.randomUUID() + "/response",
            Map.of("expectedVersion", 0, "response", " "),
            "response"),
        Arguments.of(
            "/information-requests/" + UUID.randomUUID() + "/response",
            Map.of("expectedVersion", 0, "response", "x".repeat(4001)),
            "response"),
        Arguments.of("/review", Map.of(), "expectedVersion"),
        Arguments.of("/review", Map.of("expectedVersion", -1), "expectedVersion"));
  }

  @ParameterizedTest
  @MethodSource("invalidCommands")
  void commandValidationPrecedesDomainMutation(String path, Map<String, Object> body, String field)
      throws Exception {
    assertError(command(UUID.randomUUID(), path, body), 400, "VALIDATION_ERROR")
        .andExpect(jsonPath("$.fieldErrors." + field).exists());
  }

  @Test
  void fractionalVersionIsNotSilentlyTruncated() throws Exception {
    assertError(
        command(UUID.randomUUID(), "/review", Map.of("expectedVersion", 1.5)),
        400,
        "INVALID_REQUEST");
  }

  @Test
  void submittedClaimCannotSettleRejectReviewOrRequestInformation() throws Exception {
    var c = create();
    for (String path : List.of("/settlement", "/rejection", "/review", "/information-requests")) {
      assertError(
          command(
              c.id(),
              path,
              Map.of(
                  "expectedVersion",
                  c.version(),
                  "reason",
                  "Synthetic reason",
                  "question",
                  "Synthetic question")),
          409,
          "CLAIM_CONFLICT");
    }
    assertThat(claims.get(c.id()).status()).isEqualTo(ClaimStatus.SUBMITTED);
    assertThat(claims.history(c.id())).hasSize(1);
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  void staleAssignmentAndRepeatedAssignmentHaveDifferentConflictCodes() throws Exception {
    var original = create();
    var current =
        assignment.assign(
            original.id(), new ClaimDtos.Assignment(original.version(), "first", "First"));
    assertError(
        command(
            current.id(),
            "/assignment",
            Map.of(
                "expectedVersion",
                original.version(),
                "officerId",
                "second",
                "officerName",
                "Second")),
        409,
        "STALE_CLAIM_VERSION");
    assertError(
        command(
            current.id(),
            "/assignment",
            Map.of(
                "expectedVersion",
                current.version(),
                "officerId",
                "second",
                "officerName",
                "Second")),
        409,
        "CLAIM_CONFLICT");
    assertThat(claims.get(current.id()).assignedOfficerId()).isEqualTo("first");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SETTLED", "REJECTED"})
  void terminalClaimsRejectAllMutatingCommands(String terminal) throws Exception {
    var c = review(create());
    c =
        workflow.request(
            c.id(), new ClaimDtos.RequestInformation(c.version(), "Synthetic question"));
    UUID request = c.informationRequests().get(0).id();
    c =
        workflow.provide(
            c.id(), request, new ClaimDtos.ProvideInformation(c.version(), "Synthetic answer"));
    if (terminal.equals("SETTLED")) {
      c =
          workflow.assess(
              c.id(),
              new ClaimDtos.Assessment(c.version(), BigDecimal.TEN, BigDecimal.TEN, "Covered"));
      c = workflow.settle(c.id(), new ClaimDtos.Version(c.version()));
    } else c = workflow.reject(c.id(), new ClaimDtos.Rejection(c.version(), "Not covered"));
    for (String path :
        List.of(
            "/assignment",
            "/review",
            "/information-requests",
            "/information-requests/" + request + "/response",
            "/assessment",
            "/settlement",
            "/rejection")) {
      assertError(
          command(
              c.id(),
              path,
              Map.of(
                  "expectedVersion",
                  c.version(),
                  "officerId",
                  "officer",
                  "officerName",
                  "Officer",
                  "question",
                  "Question",
                  "response",
                  "Answer",
                  "estimatedLiability",
                  10,
                  "approvedSettlementAmount",
                  10,
                  "reason",
                  "Reason")),
          409,
          "CLAIM_CONFLICT");
    }
    assertThat(claims.get(c.id()).status().name()).isEqualTo(terminal);
    assertThat(claims.get(c.id()).version()).isEqualTo(c.version());
  }

  @Test
  void nonexistentAndWrongClaimRequestsReturn404EvenWithoutOpenRequest() throws Exception {
    var first = review(create());
    first =
        workflow.request(first.id(), new ClaimDtos.RequestInformation(first.version(), "Question"));
    var second = review(create());
    UUID firstRequest = first.informationRequests().get(0).id();
    assertError(
        command(
            first.id(),
            "/information-requests/" + UUID.randomUUID() + "/response",
            Map.of("expectedVersion", first.version(), "response", "Answer")),
        404,
        "INFORMATION_REQUEST_NOT_FOUND");
    assertError(
        command(
            second.id(),
            "/information-requests/" + firstRequest + "/response",
            Map.of("expectedVersion", second.version(), "response", "Answer")),
        404,
        "INFORMATION_REQUEST_NOT_FOUND");
  }

  @Test
  void duplicateResponseAndOldAnsweredRequestDoNotCloseNewRequest() throws Exception {
    var c = review(create());
    c = workflow.request(c.id(), new ClaimDtos.RequestInformation(c.version(), "First question"));
    UUID first = c.informationRequests().get(0).id();
    c = workflow.provide(c.id(), first, new ClaimDtos.ProvideInformation(c.version(), "Answer"));
    assertError(
        command(
            c.id(),
            "/information-requests/" + first + "/response",
            Map.of("expectedVersion", c.version(), "response", "Again")),
        409,
        "CLAIM_CONFLICT");
    c = workflow.request(c.id(), new ClaimDtos.RequestInformation(c.version(), "Second question"));
    assertError(
        command(
            c.id(),
            "/information-requests/" + first + "/response",
            Map.of("expectedVersion", c.version(), "response", "Again")),
        409,
        "CLAIM_CONFLICT");
    assertThat(claims.get(c.id()).status()).isEqualTo(ClaimStatus.INFORMATION_REQUIRED);
    assertThat(claims.get(c.id()).informationRequests().get(1).status().name()).isEqualTo("OPEN");
  }

  private ClaimDtos.Detail create() {
    return claims.create(
        new ClaimDtos.Create(
            ClaimType.MOTOR,
            Market.SG,
            "Synthetic",
            "Incident",
            LocalDate.now().minusDays(1),
            BigDecimal.TEN));
  }

  private ClaimDtos.Detail review(ClaimDtos.Detail c) {
    c = assignment.assign(c.id(), new ClaimDtos.Assignment(c.version(), "officer", "Officer"));
    return workflow.review(c.id(), new ClaimDtos.Version(c.version()));
  }

  private ResultActions command(UUID id, String path, Map<String, ?> body) throws Exception {
    return mvc.perform(
        post("/api/claims/" + id + path)
            .contentType("application/json")
            .content(json.writeValueAsString(body)));
  }

  private ResultActions assertError(ResultActions result, int status, String code)
      throws Exception {
    result
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.timestamp").isString())
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.correlationId").isString())
        .andExpect(jsonPath("$.fieldErrors").isMap());
    if (code != null) result.andExpect(jsonPath("$.code").value(code));
    return result;
  }
}
