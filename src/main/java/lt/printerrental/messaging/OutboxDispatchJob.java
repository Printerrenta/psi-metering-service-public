package lt.printerrental.messaging;

import org.slf4j.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class OutboxDispatchJob {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatchJob.class);
  private final OutboxBatchPublisher publisher;

  public OutboxDispatchJob(OutboxBatchPublisher publisher) {
    this.publisher = publisher;
  }

  @Scheduled(
      fixedDelayString = "${OUTBOX_POLL_MS:5000}",
      initialDelayString = "${OUTBOX_INITIAL_DELAY_MS:5000}")
  public void dispatch() {
    try {
      publisher.publishPending();
    } catch (RuntimeException error) {
      LOG.warn("Outbox dispatch deferred; committed events will be retried");
    }
  }
}
