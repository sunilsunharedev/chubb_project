package com.chubb.claims.workflow;

import com.chubb.claims.common.DomainException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "information_requests")
public class InformationRequest {
  public enum Status {
    OPEN,
    PROVIDED
  }

  @Id private UUID id;

  @Column(nullable = false)
  private String question;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(length = 4000)
  private String response;

  @Column(nullable = false)
  private Instant requestedAt;

  private Instant providedAt;

  protected InformationRequest() {}

  public InformationRequest(String question, Instant now) {
    if (question == null || question.isBlank()) throw new DomainException("Question is required");
    this.id = UUID.randomUUID();
    this.question = question;
    this.status = Status.OPEN;
    this.requestedAt = now;
  }

  public void provide(String answer, Instant now) {
    if (status != Status.OPEN)
      throw new InvalidClaimStateException("Information request is already answered");
    if (answer == null || answer.isBlank())
      throw new DomainException("Information response is required");
    response = answer;
    providedAt = now;
    status = Status.PROVIDED;
  }

  public UUID getId() {
    return id;
  }

  public String getQuestion() {
    return question;
  }

  public Status getStatus() {
    return status;
  }

  public String getResponse() {
    return response;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getProvidedAt() {
    return providedAt;
  }
}
