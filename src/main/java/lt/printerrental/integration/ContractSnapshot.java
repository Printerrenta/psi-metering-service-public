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
    List<Terms> terms) {
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
  }
}
