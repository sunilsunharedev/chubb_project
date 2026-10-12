package com.chubb.claims.workflow;

import com.chubb.claims.common.NotFoundException;

public class InformationRequestNotFoundException extends NotFoundException {
  public InformationRequestNotFoundException() {
    super("Information request not found for this claim");
  }
}
