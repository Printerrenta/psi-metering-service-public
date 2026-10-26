package lt.printerrental.metering.api;

import java.util.*;
import lt.printerrental.metering.application.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metering")
public class MeteringController {
  private final MeteringApplication service;
  private final UsagePolicyService policies;

  public MeteringController(MeteringApplication service, UsagePolicyService policies) {
    this.service = service;
    this.policies = policies;
  }

  @PostMapping("/contracts/{id}/sync")
  public MeteringApplication.View sync(@PathVariable UUID id) {
    return service.sync(id);
  }

  @GetMapping("/contracts/{id}")
  public MeteringApplication.View view(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.view(id, page, size);
  }

  @PostMapping("/contracts/{id}/readings")
  public MeteringApplication.Result submit(
      @PathVariable UUID id, @RequestBody MeteringApplication.Submit input) {
    return service.submit(id, input);
  }

  @PostMapping("/readings/{id}/review")
  public MeteringApplication.Result review(
      @PathVariable UUID id, @RequestBody MeteringApplication.Review input) {
    return service.review(id, input);
  }

  @GetMapping("/review-queue")
  public List<MeterReading> queue(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return service.queue(page, size);
  }

  @PostMapping("/policies/{scope}")
  public UsagePolicyRepository.Policy policy(
      @PathVariable String scope, @RequestBody UsagePolicyService.Change input) {
    return policies.save(scope, input);
  }

  @GetMapping("/policies/{scope}")
  public UsagePolicyRepository.Policy policy(@PathVariable String scope, @RequestParam String key) {
    return policies.get(scope, key);
  }
}
