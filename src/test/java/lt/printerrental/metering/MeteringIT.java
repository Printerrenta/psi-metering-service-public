package lt.printerrental.metering;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import lt.printerrental.integration.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "NEON_AUTH_BASE_URL=https://auth.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.test/jwks",
      "RENTAL_SERVICE_URL=http://rental.test",
      "OUTBOX_INITIAL_DELAY_MS=3600000",
      "INBOX_INITIAL_DELAY_MS=3600000"
    })
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(MeteringIT.Config.class)
class MeteringIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=metering");
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");

  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean lt.printerrental.auth.UserIdentityResolver resolver;
  @MockitoBean ContractAccess remote;
  @MockitoBean lt.printerrental.events.EventPublisher publisher;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ContractProjectionUpdater projections;
  @Autowired ContractSnapshotStore streams;

  static class Time extends Clock {
    static Instant now = Instant.parse("2026-04-06T12:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Config {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    Clock testClock() {
      return new Time();
    }
  }

  void as(String subject, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(subject, "", "ROLE_" + role));
  }

  ContractSnapshot snapshot(UUID id) {
    return new ContractSnapshot(
        id,
        UUID.randomUUID(),
        "alice",
        "maker/large",
        true,
        true,
        "ACTIVE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 3, 31),
        null,
        LocalDate.of(2026, 1, 1),
        List.of(0L, 0L, 0L, 0L),
        1,
        List.of(
            new ContractSnapshot.Terms(
                UUID.randomUUID(),
                LocalDate.of(2026, 1, 1),
                new BigDecimal("31.00"),
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                new BigDecimal("21"),
                14)));
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Autowired lt.printerrental.metering.application.MeteringApplication app;
  @Autowired lt.printerrental.metering.application.UsagePolicyService policies;
  @Autowired lt.printerrental.metering.application.ReadingReminderService reminders;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  UUID contract;
  ContractSnapshot source;

  @BeforeEach
  void setup() {
    Time.now = Instant.parse("2026-04-06T12:00:00Z");
    db.execute(
        "TRUNCATE"
            + " metering.contracts,metering.usage_policies,metering.outbox_events,metering.inbox_events"
            + " CASCADE");
    as("employee", "EMPLOYEE");
    contract = UUID.randomUUID();
    source = snapshot(contract);
    projections.receive(source);
    policies.save(
        "MODEL",
        new lt.printerrental.metering.application.UsagePolicyService.Change(
            "maker/large", BigDecimal.ZERO, new BigDecimal("10000"), 0));
    as("alice", "CUSTOMER");
  }

  lt.printerrental.metering.application.MeteringApplication.Submit input(
      long version, LocalDate date, long mono, UUID replaces) {
    return new lt.printerrental.metering.application.MeteringApplication.Submit(
        UUID.randomUUID(), version, date, List.of(mono, 0L, 0L, 0L), replaces);
  }

  @Test
  void acceptsOwnedReadingAndAtomicallyRecordsOnlyOneEventOnRetry() {
    var input = input(0, LocalDate.of(2026, 1, 31), 310, null);
    var result = app.submit(contract, input);
    assertThat(result.reading().status()).isEqualTo("ACCEPTED");
    assertThat(app.submit(contract, input).reading().id()).isEqualTo(result.reading().id());
    assertThat(db.queryForObject("SELECT count(*) FROM metering.outbox_events", Long.class))
        .isEqualTo(1);
    as("bob", "CUSTOMER");
    assertThatThrownBy(() -> app.view(contract, 0, 20))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void correctionKeepsHistoryAndCannotCrossNextAcceptedCounter() {
    var jan = app.submit(contract, input(0, LocalDate.of(2026, 1, 31), 310, null));
    app.submit(contract, input(1, LocalDate.of(2026, 2, 28), 590, null));
    var corrected =
        app.submit(contract, input(2, LocalDate.of(2026, 1, 31), 300, jan.reading().id()));
    assertThat(corrected.reading().status()).isEqualTo("ACCEPTED");
    assertThat(
            db.queryForObject(
                "SELECT status FROM metering.readings WHERE id=?",
                String.class,
                jan.reading().id()))
        .isEqualTo("SUPERSEDED");
    assertThatThrownBy(
            () ->
                app.submit(
                    contract, input(3, LocalDate.of(2026, 1, 31), 600, corrected.reading().id())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM metering.readings", Long.class))
        .isEqualTo(3);
  }

  @Test
  void lowUsageNeedsStaffReviewAndDeviceOverrideHasPriority() {
    as("employee", "EMPLOYEE");
    policies.save(
        "MODEL",
        new lt.printerrental.metering.application.UsagePolicyService.Change(
            "maker/large", new BigDecimal("100"), new BigDecimal("10000"), 1));
    as("alice", "CUSTOMER");
    var pending = app.submit(contract, input(0, LocalDate.of(2026, 1, 31), 2, null));
    assertThat(pending.reading().status()).isEqualTo("PENDING");
    assertThatThrownBy(
            () ->
                app.review(
                    pending.reading().id(),
                    new lt.printerrental.metering.application.MeteringApplication.Review(
                        1, true, "Verified counter")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    as("employee", "EMPLOYEE");
    app.review(
        pending.reading().id(),
        new lt.printerrental.metering.application.MeteringApplication.Review(
            1, true, "Verified counter"));
    policies.save(
        "DEVICE",
        new lt.printerrental.metering.application.UsagePolicyService.Change(
            source.printerId().toString(), BigDecimal.ZERO, new BigDecimal("10000"), 0));
    as("alice", "CUSTOMER");
    assertThat(
            app.submit(contract, input(2, LocalDate.of(2026, 2, 28), 4, null)).reading().status())
        .isEqualTo("ACCEPTED");
  }

  @Test
  void staleConcurrentSubmissionsCannotCreateTwoWinningReadings() throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int n = 0; n < 2; n++)
        tasks.add(
            pool.submit(
                () -> {
                  as("alice", "CUSTOMER");
                  gate.await();
                  try {
                    app.submit(contract, input(0, LocalDate.of(2026, 1, 31), 310, null));
                    return true;
                  } catch (lt.printerrental.api.ApiProblem e) {
                    assertThat(e.status()).isEqualTo(409);
                    return false;
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      gate.countDown();
      int succeeded = 0;
      for (var t : tasks) if (t.get()) succeeded++;
      assertThat(succeeded).isEqualTo(1);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM metering.readings WHERE status='ACCEPTED'", Long.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void rollbackRemovesBothReadingAndItsOutboxEvent() {
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              app.submit(contract, input(0, LocalDate.of(2026, 1, 31), 310, null));
              tx.setRollbackOnly();
            });
    assertThat(db.queryForObject("SELECT count(*) FROM metering.readings", Long.class)).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM metering.outbox_events", Long.class))
        .isZero();
  }

  @Test
  void remindersAreDurableAndDoNotRepeatForTheSameDeadline() {
    Time.now = Instant.parse("2026-04-01T12:00:00Z");
    assertThat(reminders.remind()).isEqualTo(1);
    assertThat(reminders.remind()).isZero();
    Time.now = Instant.parse("2026-04-05T12:00:00Z");
    assertThat(reminders.remind()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM metering.outbox_events WHERE"
                    + " event_type='MeterReadingReminder'",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  void malformedAndForeignHttpRequestsAreRejected() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/metering/contracts/" + contract + "/readings")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("alice")
                        .roles("CUSTOMER"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/metering/contracts/" + contract)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("bob")
                        .roles("CUSTOMER")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }

  @Test
  void completedUsageWindowKeepsLateHistoryButDoesNotUseItAsNextCounter() {
    var jan = app.submit(contract, input(0, LocalDate.of(2026, 1, 31), 310, null));
    var ended =
        new ContractSnapshot(
            source.contractId(),
            source.printerId(),
            source.ownerSubject(),
            source.modelKey(),
            true,
            true,
            "COMPLETED",
            source.startDate(),
            source.endDate(),
            LocalDate.of(2026, 1, 15),
            source.baselineDate(),
            source.baseline(),
            2,
            source.terms());
    projections.receive(ended);
    assertThat(app.view(contract, 0, 20).readings().get(0).status())
        .isEqualTo("OUTSIDE_USAGE_WINDOW");
    var result = app.submit(contract, input(2, LocalDate.of(2026, 1, 15), 200, null));
    assertThat(result.reading().status()).isEqualTo("ACCEPTED");
  }

  @Test
  void aNeonAdminCannotInjectPrivateBrokerEvents() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/events")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }
}
