package lt.printerrental.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Service-owned JSON snapshot. No foreign entity or shared Java library. */
public record ContractSnapshot(
    UUID contractId,
    UUID printerId,
    String ownerSubject,
    String modelKey,
    boolean colorCapable,
    boolean a3Capable,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    LocalDate completedReadingDate,
    LocalDate baselineDate,
    List<Long> baseline,
    long revision,
    List<Terms> terms,
    List<Long> finalCounters) {
  public ContractSnapshot(UUID contractId, UUID printerId, String ownerSubject, String modelKey,
      boolean colorCapable, boolean a3Capable, String status, LocalDate startDate, LocalDate endDate,
      LocalDate completedReadingDate, LocalDate baselineDate, List<Long> baseline, long revision,
      List<Terms> terms) {
    this(contractId, printerId, ownerSubject, modelKey, colorCapable, a3Capable, status, startDate,
        endDate, completedReadingDate, baselineDate, baseline, revision, terms, null);
  }

  public record Terms(
      UUID id,
      LocalDate effectiveFrom,
      BigDecimal monthlyFee,
      BigDecimal monoPrice,
      BigDecimal colorPrice,
      BigDecimal vatPercent,
      int paymentTermDays) {}

  public ContractSnapshot {
    baseline = List.copyOf(baseline);
    terms = List.copyOf(terms);
    if (finalCounters != null) {
      finalCounters = List.copyOf(finalCounters);
      if (!"COMPLETED".equals(status) || completedReadingDate == null
          || finalCounters.size() != 4 || finalCounters.stream().anyMatch(n -> n == null || n < 0))
        throw new IllegalArgumentException("Invalid completion counters");
    }
  }
}
