package lt.printerrental.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lt.printerrental.events.DomainEvent;
import org.springframework.stereotype.Component;

@Component
public class MeteringEventHandler implements IncomingEventHandler {
  private final ContractSnapshotStore store;
  private final ObjectMapper json;

  public MeteringEventHandler(ContractSnapshotStore store, ObjectMapper json) {
    this.store = store;
    this.json = json;
  }

  public boolean supports(String type) {
    return "ContractAccountingChanged".equals(type);
  }

  public boolean apply(DomainEvent event) {
    var s = json.convertValue(event.data().get("snapshot"), ContractSnapshot.class);
    if (!event.aggregateId().equals(s.contractId())
        || !java.util.Objects.equals(event.ownerSubject(), s.ownerSubject()))
      throw new IllegalArgumentException("Event owner or aggregate mismatch");
    store.receive(s);
    return true;
  }
}
