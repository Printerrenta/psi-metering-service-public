package lt.printerrental.integration;

import lt.printerrental.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DurableEventInbox {
  private static final Logger LOG = LoggerFactory.getLogger(DurableEventInbox.class);
  private final EventInboxRepository repository;
  private final IncomingEventHandler handler;
  private final TransactionTemplate requiresNew;

  public DurableEventInbox(
      EventInboxRepository repository,
      IncomingEventHandler handler,
      PlatformTransactionManager transactions) {
    this.repository = repository;
    this.handler = handler;
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public void accept(DomainEvent event) {
    if (event == null
        || event.type() == null
        || event.data() == null
        || event.ownerSubject() == null
        || event.ownerSubject().isBlank()
        || event.ownerSubject().length() > 255
        || event.eventId() == null
        || event.aggregateId() == null
        || event.occurredAt() == null
        || event.schemaVersion() != 1
        || !handler.supports(event.type())) throw new IllegalArgumentException("Unsupported event");
    if (repository.insert(event) && handler.apply(event)) repository.processed(event.eventId());
  }

  public void retryPending() {
    for (var event : repository.pending()) {
      try {
        requiresNew.executeWithoutResult(
            status -> {
              if (handler.apply(event)) repository.processed(event.eventId());
            });
      } catch (RuntimeException error) {
        LOG.warn(
            "Failed to process pending inbox event {} of type {}",
            event.eventId(),
            event.type(),
            error);
      }
    }
  }
}
