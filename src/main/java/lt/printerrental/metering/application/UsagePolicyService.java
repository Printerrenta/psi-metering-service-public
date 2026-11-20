package lt.printerrental.metering.application;

import java.math.BigDecimal;
import java.util.*;
import lt.printerrental.auth.*;
import lt.printerrental.metering.domain.ReadingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsagePolicyService {
  private static final BigDecimal MAX_PER_DAY = new BigDecimal("9999999999999999.9999");
  private final UsagePolicyRepository repository;
  private final UserAccess access;
  private final CurrentUser user;

  public UsagePolicyService(UsagePolicyRepository repository, UserAccess access, CurrentUser user) {
    this.repository = repository;
    this.access = access;
    this.user = user;
  }

  public record Change(
      String key, BigDecimal minimumPerDay, BigDecimal maximumPerDay, long version) {}

  @Transactional
  public UsagePolicyRepository.Policy save(String scope, Change input) {
    access.staff();
    if (input == null
        || !Set.of("MODEL", "DEVICE").contains(scope)
        || input.key() == null
        || input.key().isBlank()
        || input.key().strip().length() > 255
        || input.version() < 0) throw new IllegalArgumentException("Invalid policy key");
    new ReadingPolicy.Bounds(input.minimumPerDay(), input.maximumPerDay());
    if (input.minimumPerDay().scale() > 4 || input.maximumPerDay().scale() > 4)
      throw new IllegalArgumentException("Policy precision exceeds four decimals");
    if (input.minimumPerDay().compareTo(MAX_PER_DAY) > 0
        || input.maximumPerDay().compareTo(MAX_PER_DAY) > 0)
      throw new IllegalArgumentException("Policy bounds exceed maximum supported value");
    var key = input.key().strip().toLowerCase(Locale.ROOT);
    if (scope.equals("DEVICE")) {
      try {
        key = UUID.fromString(key).toString();
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Device policy key must be a valid UUID");
      }
    }
    return repository.save(
        scope, key, input.minimumPerDay(), input.maximumPerDay(), input.version(), user.subject());
  }

  public Optional<UsagePolicyRepository.Policy> resolve(UUID printer, String model) {
    return repository.find("DEVICE", printer.toString()).or(() -> repository.find("MODEL", model));
  }

  public UsagePolicyRepository.Policy get(String scope, String key) {
    access.staff();
    if (!java.util.Set.of("MODEL", "DEVICE").contains(scope)
        || key == null
        || key.isBlank()
        || key.strip().length() > 255)
      throw new IllegalArgumentException("Invalid policy key");
    return repository
        .find(scope, key.strip().toLowerCase(java.util.Locale.ROOT))
        .orElseThrow(() -> new lt.printerrental.api.ApiProblem(404, "Usage policy not found"));
  }
}
