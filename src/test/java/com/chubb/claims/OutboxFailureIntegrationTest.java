package com.chubb.claims;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chubb.claims.claim.*;
import com.chubb.claims.event.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"claims.events.enabled=true", "claims.events.poll-delay-ms=3600000"})
@ActiveProfiles("test")
class OutboxFailureIntegrationTest {
  @Autowired ClaimService claims;
  @Autowired ClaimRepository repository;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxPublisher publisher;
  @MockitoBean KafkaTemplate<String, String> kafka;
  @MockitoBean org.springframework.scheduling.TaskScheduler scheduler;

  @Test
  void kafkaFailurePersistsRetryMetadataAndDoesNotUndoCommittedClaim() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("private broker")));
    var claim =
        claims.create(
            new ClaimDtos.Create(
                ClaimType.MOTOR,
                Market.SG,
                "Synthetic",
                "Incident",
                LocalDate.now().minusDays(1),
                BigDecimal.TEN));
    // No test transaction: this command has committed before the publisher runs.
    assertThat(repository.existsById(claim.id())).isTrue();
    publisher.publishPending();
    var events = outbox.findAll();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getPublishedAt()).isNull();
    assertThat(events.get(0).getAttempts()).isEqualTo(1);
    assertThat(claims.get(claim.id()).status()).isEqualTo(ClaimStatus.SUBMITTED);
    assertThat(claims.history(claim.id())).hasSize(1);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    publisher.publishPending();
    var recovered = outbox.findAll().get(0);
    assertThat(recovered.getPublishedAt()).isNotNull();
    assertThat(recovered.getAttempts()).isEqualTo(2);
  }
}
