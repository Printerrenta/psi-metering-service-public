package lt.printerrental.metering.domain;

import java.math.BigDecimal;
import java.util.*;

public record Counters(List<Long> values) {
  public Counters {
    if (values == null || values.size() != 4 || values.stream().anyMatch(n -> n == null || n < 0))
      throw new IllegalArgumentException("Four nonnegative cumulative counters required");
    values = List.copyOf(values);
  }

  public Counters difference(Counters previous) {
    var delta = new ArrayList<Long>();
    for (int i = 0; i < 4; i++)
      delta.add(Math.subtractExact(values.get(i), previous.values.get(i)));
    return new Counters(delta);
  }

  public void requireCapabilities(boolean color, boolean a3) {
    if (!color && (values.get(1) != 0 || values.get(3) != 0)
        || !a3 && (values.get(2) != 0 || values.get(3) != 0))
      throw new IllegalArgumentException("Counters contradict printer capabilities");
  }

  public BigDecimal a4Equivalent() {
    return BigDecimal.valueOf(values.get(0))
        .add(BigDecimal.valueOf(values.get(1)))
        .add(BigDecimal.valueOf(values.get(2)).multiply(BigDecimal.valueOf(2)))
        .add(BigDecimal.valueOf(values.get(3)).multiply(BigDecimal.valueOf(2)));
  }
}
