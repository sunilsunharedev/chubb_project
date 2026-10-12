package com.chubb.claims.claim;

import com.chubb.claims.common.DomainException;

public class StaleClaimVersionException extends DomainException {
  public StaleClaimVersionException() {
    super("The claim has changed because it was modified by another request. Refresh and retry.");
  }
}
