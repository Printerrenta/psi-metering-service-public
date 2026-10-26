package lt.printerrental.metering.application;

import java.math.BigDecimal;
import java.util.*;

public interface UsagePolicyRepository {
  record Policy(
      String scope, String key, BigDecimal minimumPerDay, BigDecimal maximumPerDay, long version) {}

  Optional<Policy> find(String scope, String key);

  Policy save(String scope, String key, BigDecimal min, BigDecimal max, long version, String actor);
}
