package com.chubb.claims.claim;

import com.chubb.claims.common.NotFoundException;

public class ClaimNotFoundException extends NotFoundException {
  public ClaimNotFoundException() {
    super("Claim not found");
  }
}
