package lt.printerrental.integration;

import lt.printerrental.events.DomainEvent;

public interface IncomingEventHandler {
  boolean apply(DomainEvent event);

  boolean supports(String type);
}
