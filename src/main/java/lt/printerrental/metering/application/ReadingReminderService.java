package lt.printerrental.metering.application;

import java.time.*;
import java.util.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.ContractSnapshotStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingReminderService {
  private final ContractSnapshotStore streams;
  private final ReadingRepository readings;
  private final EventRecorder events;
  private final Clock clock;

  public ReadingReminderService(
      ContractSnapshotStore streams,
      ReadingRepository readings,
      EventRecorder events,
      Clock clock) {
    this.streams = streams;
    this.readings = readings;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public int remind() {
    var today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Vilnius")));
    if (today.getDayOfMonth() != 1 && today.getDayOfMonth() != 5) return 0;
    var month = YearMonth.from(today).minusMonths(1);
    int sent = 0;
    for (var id : streams.ids()) {
      var s = streams.lock(id).snapshot();
      var end = s.completedReadingDate() == null ? s.endDate() : s.completedReadingDate();
      var cutoff = end.isBefore(month.atEndOfMonth()) ? end : month.atEndOfMonth();
      if (s.status().equals("DRAFT")
          || s.startDate().isAfter(cutoff)
          || cutoff.isBefore(month.atDay(1))
          || readings.onDate(id, cutoff).isPresent()) continue;
      var kind = today.getDayOfMonth() == 1 ? "START" : "DEADLINE";
      if (readings.reminder(id, month.atDay(1), kind)) {
        events.record(
            "MeterReadingReminder",
            id,
            s.ownerSubject(),
            Map.of(
                "period",
                month.toString(),
                "asOf",
                cutoff.toString(),
                "deadline",
                month.plusMonths(1).atDay(5).toString()));
        sent++;
      }
    }
    return sent;
  }
}
