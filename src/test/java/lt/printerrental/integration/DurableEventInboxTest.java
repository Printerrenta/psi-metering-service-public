package lt.printerrental.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import lt.printerrental.events.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DurableEventInboxTest {
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

  private DomainEvent event(String owner) {
    return new DomainEvent(
        UUID.randomUUID(),
        1,
        "ContractAccountingChanged",
        Instant.parse("2026-04-01T12:00:00Z"),
        UUID.randomUUID(),
        owner,
        Map.of());
  }

  @Test
  void retryPendingIsolatesPoisonEventPerRequiresNewTransaction() {
    var repository = mock(EventInboxRepository.class);
    var handler = mock(IncomingEventHandler.class);
    var transactions = new RecordingTransactions();
    var inbox = new DurableEventInbox(repository, handler, transactions);

    var first = event("alice");
    var poison = event("poison");
    var second = event("bob");
    when(repository.pending()).thenReturn(List.of(first, poison, second));
    when(handler.apply(first)).thenReturn(true);
    when(handler.apply(poison)).thenThrow(new IllegalStateException("corrupt event"));
    when(handler.apply(second)).thenReturn(true);

    inbox.retryPending();

    assertThat(transactions.propagations)
        .containsExactly(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactions.commits).isEqualTo(2);
    assertThat(transactions.rollbacks).isEqualTo(1);
    verify(repository).processed(first.eventId());
    verify(repository, never()).processed(poison.eventId());
    verify(repository).processed(second.eventId());
  }
}
