package lt.printerrental.messaging;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class OutboxDispatchController {
  private final OutboxDispatchJob job;

  public OutboxDispatchController(OutboxDispatchJob job) {
    this.job = job;
  }

  @PostMapping("/internal/outbox/dispatch")
  public ResponseEntity<Void> dispatch() {
    job.dispatch();
    return ResponseEntity.noContent().build();
  }
}
