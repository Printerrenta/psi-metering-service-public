package lt.printerrental.metering.domain;

import java.math.*;
import java.util.*;

public final class ReadingPolicy {
  public record Bounds(BigDecimal minimumPerDay, BigDecimal maximumPerDay) {
    public Bounds {
      if (minimumPerDay == null
          || maximumPerDay == null
          || minimumPerDay.signum() < 0
          || maximumPerDay.compareTo(minimumPerDay) < 0)
        throw new IllegalArgumentException("Invalid model/device bounds");
    }
  }

  public List<String> inspect(
      Counters before, Counters value, Counters after, long days, Bounds bounds) {
    var delta = value.difference(before);
    if (after != null) after.difference(value);
    if (days <= 0)
      throw new IllegalArgumentException("Reading interval must contain at least one day");
    if (bounds == null) return List.of("USAGE_POLICY_MISSING");
    var amount = delta.a4Equivalent();
    var span = BigDecimal.valueOf(days);
    var warnings = new ArrayList<String>();
    if (amount.compareTo(bounds.minimumPerDay().multiply(span)) < 0)
      warnings.add("USAGE_BELOW_MODEL_RANGE");
    if (amount.compareTo(bounds.maximumPerDay().multiply(span)) > 0)
      warnings.add("USAGE_ABOVE_MODEL_RANGE");
    return List.copyOf(warnings);
  }
}
