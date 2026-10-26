package lt.printerrental.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEvent(
    UUID eventId,
    int schemaVersion,
    String type,
    Instant occurredAt,
    UUID aggregateId,
    String ownerSubject,
    Map<String, Object> data) {}
