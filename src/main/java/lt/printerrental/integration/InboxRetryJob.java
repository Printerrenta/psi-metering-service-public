package lt.printerrental.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InboxRetryJob {
  private static final Logger LOG = LoggerFactory.getLogger(InboxRetryJob.class);
  private final DurableEventInbox inbox;

  public InboxRetryJob(DurableEventInbox inbox) {
    this.inbox = inbox;
  }

  @Scheduled(
      fixedDelayString = "${INBOX_RETRY_MS:10000}",
      initialDelayString = "${INBOX_INITIAL_DELAY_MS:10000}")
  public void retry() {
    try {
      inbox.retryPending();
    } catch (RuntimeException e) {
      LOG.warn("Inbox retry deferred", e);
    }
  }
}
