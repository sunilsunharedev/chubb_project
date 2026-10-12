package com.chubb.claims.claim;

import com.chubb.claims.common.DomainException;
import com.chubb.claims.workflow.InformationRequest;
import com.chubb.claims.workflow.InformationRequestNotFoundException;
import com.chubb.claims.workflow.TransitionPolicy;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "claims")
public class Claim {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 40)
  private String claimNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ClaimType claimType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Market market;

  @Column(nullable = false, length = 200)
  private String claimantName;

  @Column(nullable = false, length = 4000)
  private String incidentDescription;

  @Column(nullable = false)
  private LocalDate incidentDate;

  @Column(nullable = false)
  private Instant reportedAt;

  @Column(nullable = false)
  private Instant updatedAt;

  private Instant closedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ClaimStatus status;

  @Column(length = 100)
  private String assignedOfficerId;

  @Column(length = 200)
  private String assignedOfficerName;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal estimatedLiability;

  @Column(precision = 19, scale = 2)
  private BigDecimal approvedSettlementAmount;

  @Column(length = 2000)
  private String decisionReason;

  @Version private Long version;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "claim_id", nullable = false)
  @OrderBy("requestedAt ASC")
  private List<InformationRequest> informationRequests = new ArrayList<>();

  protected Claim() {}

  public static Claim submit(
      ClaimType type,
      Market market,
      String name,
      String description,
      LocalDate incidentDate,
      BigDecimal estimate,
      Instant now) {
    if (type == null || market == null || incidentDate == null)
      throw new DomainException("Type, market and incident date are required");
    requireText(name, "Claimant name");
    requireText(description, "Incident description");
    requireMoney(estimate);
    if (incidentDate.isAfter(now.atZone(ZoneOffset.UTC).toLocalDate()))
      throw new DomainException("Incident date cannot be in the future");
    Claim c = new Claim();
    c.id = UUID.randomUUID();
    c.claimNumber = "CLM-" + c.id.toString().replace("-", "");
    c.claimType = type;
    c.market = market;
    c.claimantName = name;
    c.incidentDescription = description;
    c.incidentDate = incidentDate;
    c.estimatedLiability = estimate;
    c.status = ClaimStatus.SUBMITTED;
    c.reportedAt = now;
    c.updatedAt = now;
    return c;
  }

  public void checkVersion(long expected) {
    if (version == null || version != expected) throw new StaleClaimVersionException();
  }

  public void assign(String officerId, String officerName, Instant now) {
    requireText(officerId, "Officer ID");
    requireText(officerName, "Officer name");
    moveTo(ClaimStatus.ASSIGNED, now);
    assignedOfficerId = officerId;
    assignedOfficerName = officerName;
  }

  public void startReview(Instant now) {
    moveTo(ClaimStatus.UNDER_REVIEW, now);
  }

  public void requestInformation(String question, Instant now) {
    InformationRequest request = new InformationRequest(question, now);
    moveTo(ClaimStatus.INFORMATION_REQUIRED, now);
    informationRequests.add(request);
  }

  public void provideInformation(UUID requestId, String response, Instant now) {
    InformationRequest request =
        informationRequests.stream()
            .filter(r -> r.getId().equals(requestId))
            .findFirst()
            .orElseThrow(InformationRequestNotFoundException::new);
    TransitionPolicy.validate(status, ClaimStatus.UNDER_REVIEW);
    request.provide(response, now);
    moveTo(ClaimStatus.UNDER_REVIEW, now);
  }

  public void assess(BigDecimal estimate, BigDecimal approved, String reason, Instant now) {
    requireMoney(estimate);
    requireMoney(approved);
    requireText(reason, "Assessment reason");
    moveTo(ClaimStatus.APPROVED, now);
    estimatedLiability = estimate;
    approvedSettlementAmount = approved;
    decisionReason = reason;
  }

  public void settle(Instant now) {
    moveTo(ClaimStatus.SETTLED, now);
    closedAt = now;
  }

  public void reject(String reason, Instant now) {
    requireText(reason, "Rejection reason");
    moveTo(ClaimStatus.REJECTED, now);
    decisionReason = reason;
    closedAt = now;
  }

  private void moveTo(ClaimStatus next, Instant now) {
    TransitionPolicy.validate(status, next);
    status = next;
    updatedAt = now;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new DomainException(field + " is required");
  }

  private static void requireMoney(BigDecimal value) {
    if (value == null
        || value.signum() < 0
        || value.scale() > 2
        || value.precision() - value.scale() > 17)
      throw new DomainException(
          "Amount must be nonnegative with at most 17 integer and 2 fractional digits");
  }

  public UUID getId() {
    return id;
  }

  public String getClaimNumber() {
    return claimNumber;
  }

  public ClaimType getClaimType() {
    return claimType;
  }

  public Market getMarket() {
    return market;
  }

  public String getClaimantName() {
    return claimantName;
  }

  public String getIncidentDescription() {
    return incidentDescription;
  }

  public LocalDate getIncidentDate() {
    return incidentDate;
  }

  public Instant getReportedAt() {
    return reportedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public ClaimStatus getStatus() {
    return status;
  }

  public String getAssignedOfficerId() {
    return assignedOfficerId;
  }

  public String getAssignedOfficerName() {
    return assignedOfficerName;
  }

  public BigDecimal getEstimatedLiability() {
    return estimatedLiability;
  }

  public BigDecimal getApprovedSettlementAmount() {
    return approvedSettlementAmount;
  }

  public String getDecisionReason() {
    return decisionReason;
  }

  public Long getVersion() {
    return version;
  }

  public List<InformationRequest> getInformationRequests() {
    return Collections.unmodifiableList(informationRequests);
  }
}
