package lt.printerrental.metering.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ReadingReminderServiceTest {
  static class RecordingTransactions implements PlatformTransactionManager {
    final List<Integer> propagations = new ArrayList<>();
    int commits;
    int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      propagations.add(definition.getPropagationBehavior());
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

  private ContractSnapshot snapshot(UUID id, String owner) {
    return new ContractSnapshot(
        id,
        UUID.randomUUID(),
        owner,
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
  void isolatesEachContractInRequiresNewTransactionSoOneFailureDoesNotRollbackOthers() {
    var streams = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingRepository.class);
    var events = mock(EventRecorder.class);
    var transactions = new RecordingTransactions();
    var service =
        new ReadingReminderService(
            streams,
            readings,
            events,
            Clock.fixed(Instant.parse("2026-04-01T12:00:00Z"), ZoneOffset.UTC),
            transactions);

    var first = UUID.randomUUID();
    var failing = UUID.randomUUID();
    var second = UUID.randomUUID();
    when(streams.ids()).thenReturn(List.of(first, failing, second));
    when(streams.lock(first)).thenReturn(new ContractSnapshotStore.Locked(snapshot(first, "alice"), 1));
    when(streams.lock(failing)).thenThrow(new IllegalStateException("corrupt snapshot"));
    when(streams.lock(second)).thenReturn(new ContractSnapshotStore.Locked(snapshot(second, "bob"), 1));
    when(readings.onDate(any(), any())).thenReturn(Optional.empty());
    when(readings.reminder(any(), eq(LocalDate.of(2026, 3, 1)), eq("START"))).thenReturn(true);

    int sent = service.remind();

    assertThat(sent).isEqualTo(2);
    assertThat(transactions.propagations)
        .containsExactly(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactions.commits).isEqualTo(2);
    assertThat(transactions.rollbacks).isEqualTo(1);
    verify(events).record(eq("MeterReadingReminder"), eq(first), eq("alice"), any());
    verify(events).record(eq("MeterReadingReminder"), eq(second), eq("bob"), any());
  }

  @Test
  void honorsConfiguredBusinessTimezoneForReminderSchedule() {
    var streams = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingRepository.class);
    var events = mock(EventRecorder.class);
    var transactions = new RecordingTransactions();
    // 2026-04-01T02:00:00Z is 2026-03-31 in Pacific/Honolulu (not day 1 or 5)
    var service =
        new ReadingReminderService(
            streams,
            readings,
            events,
            Clock.fixed(Instant.parse("2026-04-01T02:00:00Z"), ZoneOffset.UTC),
            transactions,
            "Pacific/Honolulu");

    assertThat(service.remind()).isZero();
    verifyNoInteractions(streams, readings, events);
  }
}
