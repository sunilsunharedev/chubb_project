package com.chubb.claims.claim;

import com.chubb.claims.event.EventRecorder;
import com.chubb.claims.workflow.ClaimHistoryRepository;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ClaimService {
  private final ClaimRepository claims;
  private final ClaimHistoryRepository history;
  private final Clock clock;
  private final EventRecorder events;

  public ClaimService(
      ClaimRepository claims, ClaimHistoryRepository history, Clock clock, EventRecorder events) {
    this.claims = claims;
    this.history = history;
    this.clock = clock;
    this.events = events;
  }

  public ClaimDtos.Detail create(ClaimDtos.Create input) {
    Claim c =
        Claim.submit(
            input.claimType(),
            input.market(),
            input.claimantName(),
            input.incidentDescription(),
            input.incidentDate(),
            input.estimatedLiability(),
            clock.instant());
    claims.saveAndFlush(c);
    record(c, null, "ClaimCreated");
    return ClaimDtos.Detail.from(c);
  }

  public ClaimDtos.Detail change(
      UUID id, long expectedVersion, String operation, Consumer<Claim> command) {
    Claim c = find(id);
    c.checkVersion(expectedVersion);
    ClaimStatus from = c.getStatus();
    command.accept(c);
    claims.flush();
    record(c, from, operation);
    return ClaimDtos.Detail.from(c);
  }

  private void record(Claim c, ClaimStatus from, String operation) {
    events.record(c, from, operation, clock.instant());
  }

  @Transactional(readOnly = true)
  public ClaimDtos.Detail get(UUID id) {
    return ClaimDtos.Detail.from(find(id));
  }

  @Transactional(readOnly = true)
  public ClaimDtos.StatusView status(UUID id) {
    Claim c = find(id);
    return new ClaimDtos.StatusView(c.getId(), c.getStatus(), c.getVersion(), c.getUpdatedAt());
  }

  @Transactional(readOnly = true)
  public ClaimDtos.ClaimPage list(
      ClaimStatus status, String officerId, boolean unassigned, int page, int size) {
    Specification<Claim> spec =
        (root, query, cb) -> {
          var conditions = new ArrayList<jakarta.persistence.criteria.Predicate>();
          if (status != null) conditions.add(cb.equal(root.get("status"), status));
          if (officerId != null) conditions.add(cb.equal(root.get("assignedOfficerId"), officerId));
          if (unassigned) conditions.add(cb.isNull(root.get("assignedOfficerId")));
          return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    Page<Claim> result =
        claims.findAll(spec, PageRequest.of(page, size, Sort.by("reportedAt", "id")));
    return new ClaimDtos.ClaimPage(
        result.map(ClaimDtos.Summary::from).getContent(),
        page,
        size,
        result.getTotalElements(),
        result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public List<HistoryView> history(UUID id) {
    find(id);
    return history.findByClaimIdOrderByAggregateVersionAsc(id).stream()
        .map(
            h ->
                new HistoryView(
                    h.getId(),
                    h.getOperation(),
                    h.getFromStatus(),
                    h.getToStatus(),
                    h.getOccurredAt(),
                    h.getAggregateVersion()))
        .toList();
  }

  private Claim find(UUID id) {
    return claims.findById(id).orElseThrow(ClaimNotFoundException::new);
  }

  public record HistoryView(
      UUID eventId,
      String operation,
      ClaimStatus fromStatus,
      ClaimStatus toStatus,
      java.time.Instant occurredAt,
      long aggregateVersion) {}
}
