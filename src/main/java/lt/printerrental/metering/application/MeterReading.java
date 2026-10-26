package lt.printerrental.metering.application;

import java.time.*;
import java.util.*;

public record MeterReading(
    UUID id,
    UUID contractId,
    LocalDate asOf,
    List<Long> counters,
    String status,
    UUID supersedes,
    UUID requestKey,
    String submittedBy,
    List<String> warnings,
    Map<String, Object> validation,
    Instant submittedAt,
    String reviewReason) {
  public MeterReading {
    counters = List.copyOf(counters);
    warnings = List.copyOf(warnings);
    validation = Map.copyOf(validation);
  }
}
