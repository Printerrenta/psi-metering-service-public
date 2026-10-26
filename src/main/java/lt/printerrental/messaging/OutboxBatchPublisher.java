package lt.printerrental.messaging;

import lt.printerrental.events.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** At-least-once delivery: a crash after publishing may repeat the same event ID. */
@Service
public class OutboxBatchPublisher {
  private final OutboxRepository repository;
  private final EventPublisher publisher;

  public OutboxBatchPublisher(OutboxRepository repository, EventPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void publishPending() {
    // Bound each scheduler request; claim one event at a time so no queued lease expires.
    for (int count = 0; count < 5; count++) {
      var next = repository.claim();
      if (next.isEmpty()) return;
      var row = next.get();
      try {
        // The claim transaction has committed and returned its connection before HTTP.
        publisher.publish(row.event());
        repository.published(row);
      } catch (RuntimeException error) {
        repository.failed(row);
      }
    }
  }
}
