package com.chubb.claims.claim;

import static org.assertj.core.api.Assertions.*;

import com.chubb.claims.common.DomainException;
import com.chubb.claims.workflow.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClaimTest {
  private final Instant now = Instant.parse("2026-10-07T00:00:00Z");

  private Claim submitted() {
    return Claim.submit(
        ClaimType.MOTOR,
        Market.SG,
        "Test claimant",
        "Collision",
        LocalDate.of(2026, 10, 1),
        new BigDecimal("1000.00"),
        now);
  }

  private Claim reviewed() {
    Claim c = submitted();
    c.assign("officer-1", "Officer", now);
    c.startReview(now);
    return c;
  }

  @Test
  void createsSubmittedClaimWithStableIdentityAndEstimate() {
    Claim c = submitted();
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    assertThat(c.getClaimNumber()).startsWith("CLM-");
    assertThat(c.getId()).isNotNull();
    assertThat(c.getEstimatedLiability()).isEqualByComparingTo("1000.00");
  }

  @Test
  void assignsAndStartsReview() {
    Claim c = submitted();
    c.assign("officer-1", "Officer", now);
    assertThat(c.getAssignedOfficerId()).isEqualTo("officer-1");
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.ASSIGNED);
    c.startReview(now);
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
  }

  @Test
  void rejectsSettlementBeforeApprovalWithoutChangingState() {
    Claim c = reviewed();
    assertThatThrownBy(() -> c.settle(now))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("not allowed");
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
  }

  @Test
  void informationRequestMustBelongToClaimAndBeAnsweredOnce() {
    Claim c = reviewed();
    c.requestInformation("Provide repair estimate", now);
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.INFORMATION_REQUIRED);
    assertThatThrownBy(() -> c.provideInformation(UUID.randomUUID(), "Response", now))
        .isInstanceOf(InformationRequestNotFoundException.class);
    var request = c.getInformationRequests().get(0);
    c.provideInformation(request.getId(), "Repair estimate supplied", now);
    assertThat(request.getStatus()).isEqualTo(InformationRequest.Status.PROVIDED);
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    assertThatThrownBy(() -> c.provideInformation(request.getId(), "Again", now))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void cannotRequestAgainOrAssessWhileInformationOutstanding() {
    Claim c = reviewed();
    c.requestInformation("Evidence?", now);
    assertThatThrownBy(() -> c.requestInformation("More?", now))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> c.assess(BigDecimal.TEN, BigDecimal.TEN, "Covered", now))
        .isInstanceOf(DomainException.class);
    assertThat(c.getInformationRequests()).hasSize(1);
  }

  @Test
  void approvesThenSettlesAndRecordsDecision() {
    Claim c = reviewed();
    c.assess(new BigDecimal("900.00"), new BigDecimal("800.00"), "Covered loss", now);
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.APPROVED);
    c.settle(now);
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.SETTLED);
    assertThat(c.getApprovedSettlementAmount()).isEqualByComparingTo("800.00");
    assertThatThrownBy(() -> c.reject("Changed mind", now)).isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsWithReasonAndCannotReopen() {
    Claim c = reviewed();
    c.reject("Not covered", now);
    assertThat(c.getDecisionReason()).isEqualTo("Not covered");
    assertThat(c.getStatus()).isEqualTo(ClaimStatus.REJECTED);
    assertThatThrownBy(() -> c.startReview(now)).isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsFutureIncidentAndNegativeMoney() {
    assertThatThrownBy(
            () ->
                Claim.submit(
                    ClaimType.MOTOR,
                    Market.SG,
                    "A",
                    "B",
                    LocalDate.of(2026, 10, 8),
                    BigDecimal.TEN,
                    now))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () -> reviewed().assess(BigDecimal.valueOf(-1), BigDecimal.TEN, "Reason", now))
        .isInstanceOf(DomainException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = ClaimStatus.class,
      names = {"SETTLED", "REJECTED"})
  void terminalStatesRejectEveryTransition(ClaimStatus terminal) {
    for (ClaimStatus next : ClaimStatus.values())
      assertThatThrownBy(() -> TransitionPolicy.validate(terminal, next))
          .isInstanceOf(DomainException.class);
  }
}
