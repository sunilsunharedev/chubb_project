package com.chubb.claims;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chubb.claims.assignment.AssignmentService;
import com.chubb.claims.claim.*;
import com.chubb.claims.event.*;
import com.chubb.claims.workflow.ClaimHistoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class TransactionIntegrationTest {
  @Autowired ClaimService claims;
  @Autowired ClaimRepository repository;
  @Autowired AssignmentService assignment;
  @Autowired ClaimHistoryRepository history;
  @Autowired OutboxRepository outbox;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoBean EventRecorder recorder;

  @BeforeEach
  void cleanup() {
    outbox.deleteAll();
    history.deleteAll();
    repository.deleteAll();
  }

  private ClaimDtos.Create input() {
    return new ClaimDtos.Create(
        ClaimType.MOTOR,
        Market.SG,
        "Test",
        "Collision",
        LocalDate.now().minusDays(1),
        BigDecimal.TEN);
  }

  @Test
  void eventPersistenceFailureRollsBackClaimCreation() {
    doThrow(new IllegalStateException("event persistence failed"))
        .when(recorder)
        .record(any(), any(), anyString(), any());
    assertThatThrownBy(() -> claims.create(input())).isInstanceOf(IllegalStateException.class);
    assertThat(repository.count()).isZero();
  }

  @Test
  void eventPersistenceFailureRollsBackAssignment() {
    var c = claims.create(input());
    doThrow(new IllegalStateException("event persistence failed"))
        .when(recorder)
        .record(any(), any(), eq("ClaimAssigned"), any());
    assertThatThrownBy(
            () ->
                assignment.assign(
                    c.id(), new ClaimDtos.Assignment(c.version(), "officer-1", "Officer")))
        .isInstanceOf(IllegalStateException.class);
    var persisted = claims.get(c.id());
    assertThat(persisted.status()).isEqualTo(ClaimStatus.SUBMITTED);
    assertThat(persisted.assignedOfficerId()).isNull();
    assertThat(persisted.version()).isEqualTo(c.version());
  }

  @Test
  void databaseActuallyRejectsDuplicateClaimNumber() {
    var c = claims.create(input());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO claims (id, claim_number, claim_type, market, claimant_name,"
                        + " incident_description, incident_date, reported_at, updated_at, status,"
                        + " estimated_liability, version) SELECT ?, claim_number, claim_type,"
                        + " market, claimant_name, incident_description, incident_date,"
                        + " reported_at, updated_at, status, estimated_liability, version FROM"
                        + " claims WHERE id = ?",
                    UUID.randomUUID(),
                    c.id()))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void optimisticLockAllowsOnlyOneConcurrentPickup() throws Exception {
    UUID id = claims.create(input()).id();
    CyclicBarrier bothLoaded = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> pickup =
          () -> {
            try {
              new TransactionTemplate(transactionManager)
                  .executeWithoutResult(
                      tx -> {
                        Claim c = repository.findById(id).orElseThrow();
                        try {
                          bothLoaded.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                        c.assign(
                            Thread.currentThread().getName(), "Officer", java.time.Instant.now());
                        repository.flush();
                      });
              return true;
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
              return false;
            }
          };
      Future<Boolean> a = executor.submit(pickup);
      Future<Boolean> b = executor.submit(pickup);
      assertThat(java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(claims.get(id).version()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }
}
