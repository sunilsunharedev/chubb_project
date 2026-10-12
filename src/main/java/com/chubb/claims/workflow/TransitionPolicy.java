package com.chubb.claims.workflow;

import static com.chubb.claims.claim.ClaimStatus.*;

import com.chubb.claims.claim.ClaimStatus;
import java.util.Map;
import java.util.Set;

public final class TransitionPolicy {
  private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED =
      Map.of(
          SUBMITTED,
          Set.of(ASSIGNED),
          ASSIGNED,
          Set.of(UNDER_REVIEW),
          UNDER_REVIEW,
          Set.of(INFORMATION_REQUIRED, APPROVED, REJECTED),
          INFORMATION_REQUIRED,
          Set.of(UNDER_REVIEW),
          APPROVED,
          Set.of(SETTLED));

  private TransitionPolicy() {}

  public static void validate(ClaimStatus from, ClaimStatus to) {
    if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
      throw new InvalidClaimStateException(
          "Transition from " + from + " to " + to + " is not allowed");
    }
  }
}
