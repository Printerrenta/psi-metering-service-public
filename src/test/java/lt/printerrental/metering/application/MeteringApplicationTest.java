package lt.printerrental.metering.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MeteringApplicationTest {
  static class RecordingTransactions implements PlatformTransactionManager {
    final List<TransactionDefinition> definitions = new ArrayList<>();
    int commits;
    int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      definitions.add(definition);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
    }
  }

  private CurrentUser user(String subject, UserRole role) {
    return new CurrentUser() {
      @Override
      public String subject() {
        return subject;
      }

      @Override
      public boolean hasRole(UserRole candidate) {
        return candidate == role;
      }
    };
  }

  private ContractSnapshot snapshot(UUID id) {
    return new ContractSnapshot(
        id,
        UUID.randomUUID(),
        "alice",
        "maker/large",
        true,
        true,
        "ACTIVE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31),
        null,
        LocalDate.of(2026, 1, 1),
        List.of(0L, 0L, 0L, 0L),
        1,
        List.of(
            new ContractSnapshot.Terms(
                UUID.randomUUID(),
                LocalDate.of(2026, 1, 1),
                new BigDecimal("31.00"),
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                new BigDecimal("21"),
                14)));
  }

  @Test
  void syncExecutesSnapshotReadInsideRepeatableReadReadOnlyTransaction() {
    var contracts = mock(ContractAccess.class);
    var updater = mock(ContractProjectionUpdater.class);
    var streams = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingRepository.class);
    var policies = mock(UsagePolicyService.class);
    var events = mock(EventRecorder.class);
    var transactions = new RecordingTransactions();
    var alice = user("alice", UserRole.CUSTOMER);
    var app =
        new MeteringApplication(
            contracts,
            updater,
            streams,
            readings,
            policies,
            new UserAccess(alice),
            alice,
            events,
            Clock.fixed(Instant.parse("2026-04-06T12:00:00Z"), ZoneOffset.UTC),
            transactions);

    var id = UUID.randomUUID();
    var snap = snapshot(id);
    when(contracts.get(id)).thenReturn(snap);
    when(streams.find(id)).thenReturn(Optional.of(snap));
    when(streams.version(id)).thenReturn(3L);
    when(readings.history(id, 0, 20)).thenReturn(List.of());

    var view = app.sync(id);

    assertThat(view.version()).isEqualTo(3L);
    assertThat(transactions.definitions).hasSize(1);
    assertThat(transactions.definitions.get(0).isReadOnly()).isTrue();
    assertThat(transactions.definitions.get(0).getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(transactions.commits).isEqualTo(1);
    assertThat(transactions.rollbacks).isZero();
    verify(updater).receive(snap);
  }

  @Test
  void duplicatePendingReadingOnSameDateThrowsClearConflict() {
    var contracts = mock(ContractAccess.class);
    var updater = mock(ContractProjectionUpdater.class);
    var streams = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingRepository.class);
    var policies = mock(UsagePolicyService.class);
    var events = mock(EventRecorder.class);
    var transactions = new RecordingTransactions();
    var alice = user("alice", UserRole.CUSTOMER);
    var app =
        new MeteringApplication(
            contracts,
            updater,
            streams,
            readings,
            policies,
            new UserAccess(alice),
            alice,
            events,
            Clock.fixed(Instant.parse("2026-04-06T12:00:00Z"), ZoneOffset.UTC),
            transactions);

    var contractId = UUID.randomUUID();
    var date = LocalDate.of(2026, 1, 31);
    var snap = snapshot(contractId);
    var existingPending =
        new MeterReading(
            UUID.randomUUID(),
            contractId,
            date,
            List.of(2L, 0L, 0L, 0L),
            "PENDING",
            null,
            UUID.randomUUID(),
            "alice",
            List.of("USAGE_BELOW_MODEL_RANGE"),
            Map.of(),
            Instant.parse("2026-02-01T10:00:00Z"),
            null);

    when(streams.lock(contractId)).thenReturn(new ContractSnapshotStore.Locked(snap, 1L));
    when(readings.byRequest(eq(contractId), any())).thenReturn(Optional.empty());
    when(readings.pendingOnDate(contractId, date)).thenReturn(Optional.of(existingPending));

    var submit =
        new MeteringApplication.Submit(UUID.randomUUID(), 1L, date, List.of(5L, 0L, 0L, 0L), null);

    assertThatThrownBy(() -> app.submit(contractId, submit))
        .isInstanceOf(ApiProblem.class)
        .satisfies(
            error -> {
              var problem = (ApiProblem) error;
              assertThat(problem.status()).isEqualTo(409);
              assertThat(problem.getMessage()).contains("pending reading already exists");
            });
    verify(readings, never()).insert(any());
  }

  @Test
  void reviewApprovalAllowsThePendingReadingBeingReviewed() {
    var contracts = mock(ContractAccess.class);
    var updater = mock(ContractProjectionUpdater.class);
    var streams = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingRepository.class);
    var policies = mock(UsagePolicyService.class);
    var events = mock(EventRecorder.class);
    var transactions = new RecordingTransactions();
    var staff = user("employee", UserRole.EMPLOYEE);
    var now = Instant.parse("2026-04-06T12:00:00Z");
    var app =
        new MeteringApplication(
            contracts,
            updater,
            streams,
            readings,
            policies,
            new UserAccess(staff),
            staff,
            events,
            Clock.fixed(now, ZoneOffset.UTC),
            transactions);

    var contractId = UUID.randomUUID();
    var readingId = UUID.randomUUID();
    var date = LocalDate.of(2026, 1, 31);
    var snap = snapshot(contractId);
    var pending =
        new MeterReading(
            readingId,
            contractId,
            date,
            List.of(2L, 0L, 0L, 0L),
            "PENDING",
            null,
            UUID.randomUUID(),
            "alice",
            List.of("USAGE_BELOW_MODEL_RANGE"),
            Map.of(),
            Instant.parse("2026-02-01T10:00:00Z"),
            null);
    var accepted =
        new MeterReading(
            readingId,
            contractId,
            date,
            List.of(2L, 0L, 0L, 0L),
            "ACCEPTED",
            null,
            pending.requestKey(),
            "alice",
            pending.warnings(),
            Map.of(),
            pending.submittedAt(),
            "Verified");

    when(readings.find(readingId))
        .thenReturn(Optional.of(pending), Optional.of(pending), Optional.of(accepted));
    when(streams.lock(contractId)).thenReturn(new ContractSnapshotStore.Locked(snap, 1L));
    when(readings.pendingOnDate(contractId, date)).thenReturn(Optional.of(pending));
    when(readings.onDate(contractId, date)).thenReturn(Optional.empty());
    when(readings.previous(contractId, date)).thenReturn(Optional.empty());
    when(readings.next(contractId, date)).thenReturn(Optional.empty());
    when(streams.bump(contractId)).thenReturn(2L);

    var result = app.review(readingId, new MeteringApplication.Review(1L, true, "Verified"));
    assertThat(result.reading().status()).isEqualTo("ACCEPTED");
    verify(readings).review(readingId, "ACCEPTED", "employee", "Verified", now);
  }

  @Test
  void honorsConfiguredBusinessTimezoneForTodayValidation() {
    var contracts = mock(ContractAccess.class);
    var updater = mock(ContractProjectionUpdater.class);
    var streams = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingRepository.class);
    var policies = mock(UsagePolicyService.class);
    var events = mock(EventRecorder.class);
    var transactions = new RecordingTransactions();
    var alice = user("alice", UserRole.CUSTOMER);
    // 2026-04-06T02:00:00Z is 2026-04-05 in Pacific/Honolulu (UTC-10)
    var app =
        new MeteringApplication(
            contracts,
            updater,
            streams,
            readings,
            policies,
            new UserAccess(alice),
            alice,
            events,
            Clock.fixed(Instant.parse("2026-04-06T02:00:00Z"), ZoneOffset.UTC),
            transactions,
            "Pacific/Honolulu");

    var contractId = UUID.randomUUID();
    var snap = snapshot(contractId);
    when(streams.lock(contractId)).thenReturn(new ContractSnapshotStore.Locked(snap, 0L));
    when(readings.byRequest(eq(contractId), any())).thenReturn(Optional.empty());

    var futureInHonolulu =
        new MeteringApplication.Submit(
            UUID.randomUUID(), 0L, LocalDate.of(2026, 4, 6), List.of(10L, 0L, 0L, 0L), null);

    assertThatThrownBy(() -> app.submit(contractId, futureInHonolulu))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("today or earlier");
  }
}
