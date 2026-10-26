package lt.printerrental.metering.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.*;
import lt.printerrental.metering.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MeteringApplication {
  private final ContractAccess contracts;
  private final ContractProjectionUpdater updater;
  private final ContractSnapshotStore streams;
  private final ReadingRepository readings;
  private final UsagePolicyService policies;
  private final UserAccess access;
  private final CurrentUser user;
  private final EventRecorder events;
  private final Clock clock;
  private final ReadingPolicy policy = new ReadingPolicy();

  public MeteringApplication(
      ContractAccess contracts,
      ContractProjectionUpdater updater,
      ContractSnapshotStore streams,
      ReadingRepository readings,
      UsagePolicyService policies,
      UserAccess access,
      CurrentUser user,
      EventRecorder events,
      Clock clock) {
    this.contracts = contracts;
    this.updater = updater;
    this.streams = streams;
    this.readings = readings;
    this.policies = policies;
    this.access = access;
    this.user = user;
    this.events = events;
    this.clock = clock;
  }

  public record Submit(
      UUID requestKey, long version, LocalDate asOf, List<Long> counters, UUID supersedes) {}

  public record Review(long version, boolean approve, String reason) {}

  public record Result(MeterReading reading, long version) {}

  public record View(
      ContractSnapshot contract, long version, List<MeterReading> readings, int page, int size) {}

  public View sync(UUID id) {
    var snapshot = contracts.get(id);
    access.owner(snapshot.ownerSubject());
    updater.receive(snapshot);
    return view(id, 0, 20);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public View view(UUID id, int page, int size) {
    page(page, size);
    var s =
        streams
            .find(id)
            .orElseThrow(
                () -> new ApiProblem(404, "Contract accounting has not been synchronized"));
    access.owner(s.ownerSubject());
    return new View(
        s,
        streams.version(id),
        readings.history(id, page, size).stream().map(r -> display(s, r)).toList(),
        page,
        size);
  }

  @Transactional(readOnly = true)
  public List<MeterReading> queue(int page, int size) {
    access.staff();
    page(page, size);
    return readings.pending(page, size);
  }

  @Transactional
  public Result submit(UUID id, Submit input) {
    var locked = streams.lock(id);
    var s = locked.snapshot();
    access.owner(s.ownerSubject());
    if (input.requestKey() == null || input.asOf() == null)
      throw new IllegalArgumentException("Request key and reading date required");
    var counts = new Counters(input.counters());
    var duplicate = duplicate(s, id, input, counts, locked.version());
    if (duplicate.isPresent()) return duplicate.get();
    if (input.version() != locked.version())
      throw new ApiProblem(409, "Reading stream changed; reload");
    var context = validate(s, input.asOf(), counts, input.supersedes());
    var chosen = policies.resolve(s.printerId(), s.modelKey());
    var warnings = warnings(s, input.asOf(), counts, context, chosen);
    var validation = validationSnapshot(chosen);
    var status = warnings.isEmpty() ? "ACCEPTED" : "PENDING";
    var r =
        new MeterReading(
            UUID.randomUUID(),
            id,
            input.asOf(),
            counts.values(),
            status,
            input.supersedes(),
            input.requestKey(),
            user.subject(),
            warnings,
            validation,
            clock.instant(),
            null);
    if (status.equals("ACCEPTED") && input.supersedes() != null)
      readings.supersede(input.supersedes());
    readings.insert(r);
    var version = streams.bump(id);
    publish(s, r, version);
    return new Result(r, version);
  }

  @Transactional
  public Result review(UUID id, Review input) {
    access.staff();
    var original = readings.find(id).orElseThrow(() -> new ApiProblem(404, "Reading not found"));
    var locked = streams.lock(original.contractId());
    var r = readings.find(id).orElseThrow();
    if (input.version() != locked.version() || !r.status().equals("PENDING"))
      throw new ApiProblem(409, "Review is stale or reading is no longer pending");
    if (input.reason() == null
        || input.reason().strip().length() < 5
        || input.reason().strip().length() > 2000)
      throw new IllegalArgumentException("Review reason must be 5..2000 characters");
    if (input.approve()) {
      validate(locked.snapshot(), r.asOf(), new Counters(r.counters()), r.supersedes());
      if (r.supersedes() != null) readings.supersede(r.supersedes());
    }
    readings.review(
        id,
        input.approve() ? "ACCEPTED" : "REJECTED",
        user.subject(),
        input.reason().strip(),
        clock.instant());
    var version = streams.bump(r.contractId());
    var updated = readings.find(id).orElseThrow();
    publish(locked.snapshot(), updated, version);
    return new Result(updated, version);
  }

  private Optional<Result> duplicate(
      ContractSnapshot s, UUID id, Submit input, Counters counts, long version) {
    var duplicate = readings.byRequest(id, input.requestKey());
    if (duplicate.isPresent()) {
      var r = duplicate.get();
      if (!r.asOf().equals(input.asOf())
          || !r.counters().equals(counts.values())
          || !Objects.equals(r.supersedes(), input.supersedes()))
        throw new ApiProblem(409, "Idempotency key reused for different reading");
      return Optional.of(new Result(display(s, r), version));
    }
    return Optional.empty();
  }

  private Map<String, Object> validationSnapshot(Optional<UsagePolicyRepository.Policy> chosen) {
    var validation = new LinkedHashMap<String, Object>();
    validation.put("equivalentUnit", "A4");
    chosen.ifPresent(
        p -> {
          validation.put("scope", p.scope());
          validation.put("key", p.key());
          validation.put("version", p.version());
          validation.put("minimumPerDay", p.minimumPerDay());
          validation.put("maximumPerDay", p.maximumPerDay());
        });
    return validation;
  }

  private record Neighbors(MeterReading previous, MeterReading next) {}

  private Neighbors validate(ContractSnapshot s, LocalDate date, Counters counters, UUID replaces) {
    var today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Vilnius")));
    var end = s.completedReadingDate() == null ? s.endDate() : s.completedReadingDate();
    if (s.status().equals("DRAFT")
        || date.isBefore(s.startDate())
        || date.isAfter(end)
        || date.isAfter(today)
        || (!date.equals(YearMonth.from(date).atEndOfMonth()) && !date.equals(end)))
      throw new IllegalArgumentException(
          "Reading must represent a closed month or final usage date within the contract");
    counters.requireCapabilities(s.colorCapable(), s.a3Capable());
    var current = readings.onDate(s.contractId(), date);
    if (current.isPresent() && !Objects.equals(current.get().id(), replaces)
        || current.isEmpty() && replaces != null)
      throw new ApiProblem(
          409, "Correction must reference the currently accepted reading for this date");
    var previous = readings.previous(s.contractId(), date).orElse(null);
    var next = readings.next(s.contractId(), date).orElse(null);
    if (next != null && next.asOf().isAfter(end)) next = null;
    counters.difference(new Counters(previous == null ? s.baseline() : previous.counters()));
    if (next != null) new Counters(next.counters()).difference(counters);
    return new Neighbors(previous, next);
  }

  private List<String> warnings(
      ContractSnapshot s,
      LocalDate date,
      Counters value,
      Neighbors n,
      Optional<UsagePolicyRepository.Policy> chosen) {
    var bounds =
        chosen
            .map(p -> new ReadingPolicy.Bounds(p.minimumPerDay(), p.maximumPerDay()))
            .orElse(null);
    long days =
        ChronoUnit.DAYS.between(n.previous() == null ? s.startDate() : n.previous().asOf(), date)
            + (n.previous() == null ? 1 : 0);
    var result =
        new ArrayList<>(
            policy.inspect(
                new Counters(n.previous() == null ? s.baseline() : n.previous().counters()),
                value,
                n.next() == null ? null : new Counters(n.next().counters()),
                days,
                bounds));
    if (n.next() != null)
      for (var warning :
          policy.inspect(
              value,
              new Counters(n.next().counters()),
              null,
              ChronoUnit.DAYS.between(date, n.next().asOf()),
              bounds)) result.add("NEXT_INTERVAL_" + warning);
    return List.copyOf(result);
  }

  private void publish(ContractSnapshot s, MeterReading r, long version) {
    var data = new LinkedHashMap<String, Object>();
    data.put("readingId", r.id());
    data.put("asOf", r.asOf());
    data.put("counters", r.counters());
    data.put("streamRevision", version);
    if (r.supersedes() != null) data.put("supersedes", r.supersedes());
    events.record(
        r.status().equals("ACCEPTED")
            ? "MeterReadingAccepted"
            : r.status().equals("PENDING") ? "MeterReadingNeedsReview" : "MeterReadingRejected",
        s.contractId(),
        s.ownerSubject(),
        data);
  }

  private static void page(int page, int size) {
    if (page < 0 || page > 1000000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
  }

  private MeterReading display(ContractSnapshot s, MeterReading r) {
    if (r.status().equals("ACCEPTED")
        && s.completedReadingDate() != null
        && r.asOf().isAfter(s.completedReadingDate())) {
      var reasons = new java.util.ArrayList<>(r.warnings());
      reasons.add("OUTSIDE_ACTUAL_USAGE_WINDOW");
      return new MeterReading(
          r.id(),
          r.contractId(),
          r.asOf(),
          r.counters(),
          "OUTSIDE_USAGE_WINDOW",
          r.supersedes(),
          r.requestKey(),
          r.submittedBy(),
          reasons,
          r.validation(),
          r.submittedAt(),
          r.reviewReason());
    }
    return r;
  }
}
