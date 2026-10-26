package lt.printerrental.metering.api;

import java.util.Map;
import lt.printerrental.integration.DurableEventInbox;
import lt.printerrental.metering.application.ReadingReminderService;
import org.springframework.web.bind.annotation.*;

@RestController
public class MeteringMaintenanceController {
  private final ReadingReminderService reminders;
  private final DurableEventInbox inbox;
  private final lt.printerrental.messaging.OutboxDispatchJob outbox;

  public MeteringMaintenanceController(
      ReadingReminderService reminders,
      DurableEventInbox inbox,
      lt.printerrental.messaging.OutboxDispatchJob outbox) {
    this.reminders = reminders;
    this.inbox = inbox;
    this.outbox = outbox;
  }

  @PostMapping("/internal/metering/tick")
  public Map<String, Integer> tick() {
    inbox.retryPending();
    var count = reminders.remind();
    outbox.dispatch();
    return Map.of("reminders", count);
  }
}
