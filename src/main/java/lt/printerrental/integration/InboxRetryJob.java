package lt.printerrental.integration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InboxRetryJob {
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
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Inbox retry deferred");
    }
  }
}
