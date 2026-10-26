package lt.printerrental.metering.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReadingPolicyTest {
  final ReadingPolicy policy = new ReadingPolicy();

  Counters c(long a4, long a3) {
    return new Counters(List.of(a4, 0L, a3, 0L));
  }

  ReadingPolicy.Bounds bounds(String min, String max) {
    return new ReadingPolicy.Bounds(new BigDecimal(min), new BigDecimal(max));
  }

  @Test
  void largeMachineWithVeryLowUseNeedsReview() {
    assertThat(policy.inspect(c(0, 0), c(2, 0), null, 30, bounds("100", "10000")))
        .containsExactly("USAGE_BELOW_MODEL_RANGE");
  }

  @Test
  void excessiveUseNeedsReview() {
    assertThat(policy.inspect(c(0, 0), c(50000, 0), null, 1, bounds("0", "1000")))
        .containsExactly("USAGE_ABOVE_MODEL_RANGE");
  }

  @Test
  void a3CountsAsTwoA4EquivalentsAndBoundaryIsInclusive() {
    assertThat(policy.inspect(c(0, 0), c(0, 50), null, 1, bounds("100", "100"))).isEmpty();
  }

  @Test
  void decreasingCountersAreInvalidNotJustSuspicious() {
    assertThatThrownBy(() -> policy.inspect(c(10, 0), c(9, 0), null, 1, bounds("0", "100")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void correctionCannotExceedTheNextAcceptedCounter() {
    assertThatThrownBy(() -> policy.inspect(c(10, 0), c(30, 0), c(20, 0), 1, bounds("0", "100")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingConfigurationCannotSilentlyAutoAccept() {
    assertThat(policy.inspect(c(0, 0), c(1, 0), null, 1, null))
        .containsExactly("USAGE_POLICY_MISSING");
  }

  @Test
  void unsupportedPrinterCategoriesAreRejected() {
    assertThatThrownBy(() -> new Counters(List.of(0L, 1L, 0L, 0L)).requireCapabilities(false, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> c(0, 1).requireCapabilities(true, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equivalentCalculationDoesNotOverflowSignedLong() {
    assertThat(c(Long.MAX_VALUE, Long.MAX_VALUE).a4Equivalent())
        .isEqualByComparingTo(BigDecimal.valueOf(Long.MAX_VALUE).multiply(BigDecimal.valueOf(3)));
  }
}
