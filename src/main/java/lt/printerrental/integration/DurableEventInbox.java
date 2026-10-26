package lt.printerrental.integration;

import java.util.*;
import lt.printerrental.events.DomainEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DurableEventInbox {
  private final EventInboxRepository repository;
  private final IncomingEventHandler handler;

  public DurableEventInbox(EventInboxRepository repository, IncomingEventHandler handler) {
    this.repository = repository;
    this.handler = handler;
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

  @Transactional
  public void retryPending() {
    for (var event : repository.pending())
      if (handler.apply(event)) repository.processed(event.eventId());
  }
}
