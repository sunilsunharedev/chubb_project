package com.chubb.claims.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {
  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final OutboxRepository repository = mock(OutboxRepository.class);
  private final Instant now = Instant.parse("2026-10-07T00:00:00Z");
  private final OutboxPublisher publisher =
      new OutboxPublisher(
          repository, kafka, Clock.fixed(now, ZoneOffset.UTC), "claims.lifecycle.v1");

  private OutboxEvent pending() {
    return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "ClaimCreated", "{}", now);
  }

  @Test
  void marksPublishedOnlyAfterBrokerAcknowledgement() {
    var event = pending();
    when(repository.findTop20ByPublishedAtIsNullOrderByOccurredAtAsc()).thenReturn(List.of(event));
    when(kafka.send("claims.lifecycle.v1", event.getClaimId().toString(), "{}"))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    publisher.publishPending();
    assertThat(event.getPublishedAt()).isEqualTo(now);
    assertThat(event.getAttempts()).isEqualTo(1);
  }

  @Test
  void brokerFailureLeavesEventPendingAndStopsBatchForRetry() {
    var event = pending();
    var second = pending();
    when(repository.findTop20ByPublishedAtIsNullOrderByOccurredAtAsc())
        .thenReturn(List.of(event, second));
    when(kafka.send("claims.lifecycle.v1", event.getClaimId().toString(), "{}"))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    publisher.publishPending();
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    verify(kafka, never()).send("claims.lifecycle.v1", second.getClaimId().toString(), "{}");
  }

  @Test
  void sendTimeoutKeepsPendingThenSuccessfulRetryMarksPublished() throws Exception {
    var event = pending();
    when(repository.findTop20ByPublishedAtIsNullOrderByOccurredAtAsc()).thenReturn(List.of(event));
    CompletableFuture<SendResult<String, String>> timeout = mock(CompletableFuture.class);
    when(timeout.get(15, TimeUnit.SECONDS))
        .thenThrow(new TimeoutException("private broker details"));
    when(kafka.send("claims.lifecycle.v1", event.getClaimId().toString(), "{}"))
        .thenReturn(timeout);
    publisher.publishPending();
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
    when(kafka.send("claims.lifecycle.v1", event.getClaimId().toString(), "{}"))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    publisher.publishPending();
    assertThat(event.getPublishedAt()).isEqualTo(now);
    assertThat(event.getAttempts()).isEqualTo(2);
  }

  @Test
  void synchronousSendFailureDoesNotMarkPublished() {
    var event = pending();
    when(repository.findTop20ByPublishedAtIsNullOrderByOccurredAtAsc()).thenReturn(List.of(event));
    when(kafka.send("claims.lifecycle.v1", event.getClaimId().toString(), "{}"))
        .thenThrow(new org.apache.kafka.common.errors.TimeoutException("private broker address"));
    publisher.publishPending();
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
  }

  @Test
  void interruptedPublicationPreservesInterruptAndPendingRecord() throws Exception {
    var event = pending();
    when(repository.findTop20ByPublishedAtIsNullOrderByOccurredAtAsc()).thenReturn(List.of(event));
    CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
    when(future.get(15, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
    when(kafka.send("claims.lifecycle.v1", event.getClaimId().toString(), "{}")).thenReturn(future);
    try {
      publisher.publishPending();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(event.getPublishedAt()).isNull();
      assertThat(event.getAttempts()).isEqualTo(1);
    } finally {
      Thread.interrupted();
    }
  }
}
