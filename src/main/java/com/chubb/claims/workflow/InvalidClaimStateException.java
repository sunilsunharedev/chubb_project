package com.chubb.claims.workflow;

import com.chubb.claims.common.DomainException;

public class InvalidClaimStateException extends DomainException {
  public InvalidClaimStateException(String message) {
    super(message);
  }
}
