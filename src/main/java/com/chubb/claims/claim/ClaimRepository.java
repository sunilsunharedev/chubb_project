package com.chubb.claims.claim;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClaimRepository
    extends JpaRepository<Claim, UUID>, JpaSpecificationExecutor<Claim> {
  Optional<Claim> findByClaimNumber(String claimNumber);
}
