package lt.printerrental.metering.application;

import java.time.LocalDate;
import java.util.*;

public interface ReadingRepository {
  Optional<MeterReading> find(UUID id);

  Optional<MeterReading> byRequest(UUID contract, UUID key);

  Optional<MeterReading> onDate(UUID contract, LocalDate date);

  Optional<MeterReading> previous(UUID contract, LocalDate date);

  Optional<MeterReading> next(UUID contract, LocalDate date);

  List<MeterReading> history(UUID contract, int page, int size);

  List<MeterReading> pending(int page, int size);

  void insert(MeterReading reading);

  void supersede(UUID id);

  void review(UUID id, String status, String actor, String reason, java.time.Instant now);

  boolean reminder(UUID contract, LocalDate month, String kind);
}
