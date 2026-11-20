package lt.printerrental.metering.application;

import java.time.*;
import java.util.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.ContractSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReadingReminderService {
  private static final Logger LOG = LoggerFactory.getLogger(ReadingReminderService.class);
  private final ContractSnapshotStore streams;
  private final ReadingRepository readings;
  private final EventRecorder events;
  private final Clock clock;
  private final TransactionTemplate requiresNew;
  private final ZoneId businessZone;

  @Autowired
  public ReadingReminderService(
      ContractSnapshotStore streams,
      ReadingRepository readings,
      EventRecorder events,
      Clock clock,
      PlatformTransactionManager transactions,
      @Value("${app.business-zone:Europe/Vilnius}") String businessZone) {
    this.streams = streams;
    this.readings = readings;
    this.events = events;
    this.clock = clock;
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.businessZone = ZoneId.of(businessZone);
  }

  public ReadingReminderService(
      ContractSnapshotStore streams,
      ReadingRepository readings,
      EventRecorder events,
      Clock clock,
      PlatformTransactionManager transactions) {
    this(streams, readings, events, clock, transactions, "Europe/Vilnius");
  }

  public int remind() {
    var today = LocalDate.now(clock.withZone(businessZone));
    if (today.getDayOfMonth() != 1 && today.getDayOfMonth() != 5) return 0;
    var month = YearMonth.from(today).minusMonths(1);
    var kind = today.getDayOfMonth() == 1 ? "START" : "DEADLINE";
    int sent = 0;
    for (var id : streams.ids()) {
      try {
        var recorded = requiresNew.execute(status -> remindContract(id, month, kind));
        if (Boolean.TRUE.equals(recorded)) sent++;
      } catch (RuntimeException error) {
        LOG.warn("Failed to process meter reading reminder for contract {}", id, error);
      }
    }
    return sent;
  }

  private boolean remindContract(UUID id, YearMonth month, String kind) {
    var s = streams.lock(id).snapshot();
    var end = s.completedReadingDate() == null ? s.endDate() : s.completedReadingDate();
    var cutoff = end.isBefore(month.atEndOfMonth()) ? end : month.atEndOfMonth();
    if (s.status().equals("DRAFT")
        || s.startDate().isAfter(cutoff)
        || cutoff.isBefore(month.atDay(1))
        || readings.onDate(id, cutoff).isPresent()) return false;
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
      return true;
    }
    return false;
  }
}
