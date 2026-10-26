package lt.printerrental.events;

import java.util.Map;
import java.util.UUID;

public interface EventRecorder {
  void record(String type, UUID aggregateId, String ownerSubject, Map<String, Object> data);
}
