package lt.printerrental.messaging;

import java.time.Clock;
import java.util.*;
import lt.printerrental.events.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class JdbcEventRecorder implements EventRecorder {
  private final OutboxRepository repository;
  private final Clock clock;

  public JdbcEventRecorder(OutboxRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String type, UUID aggregateId, String ownerSubject, Map<String, Object> data) {
    repository.insert(
        new DomainEvent(
            UUID.randomUUID(),
            1,
            type,
            clock.instant(),
            aggregateId,
            ownerSubject,
            Map.copyOf(data)));
  }
}
