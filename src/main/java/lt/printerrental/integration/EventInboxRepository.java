package lt.printerrental.integration;

import java.util.*;
import lt.printerrental.events.DomainEvent;

public interface EventInboxRepository {
  boolean insert(DomainEvent event);

  void processed(UUID eventId);

  List<DomainEvent> pending();
}
