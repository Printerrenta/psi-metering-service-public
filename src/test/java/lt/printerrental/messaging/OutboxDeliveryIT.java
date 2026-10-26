package lt.printerrental.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import lt.printerrental.events.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringJUnitConfig(OutboxDeliveryIT.Config.class)
class OutboxDeliveryIT {
  static final PostgreSQLContainer<?> POSTGRES =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource() {
      var ds = new HikariDataSource();
      POSTGRES.start();
      ds.setJdbcUrl(POSTGRES.getJdbcUrl() + "?currentSchema=outbox_metering");
      ds.setUsername(POSTGRES.getUsername());
      ds.setPassword(POSTGRES.getPassword());
      ds.setMaximumPoolSize(1);
      ds.setMinimumIdle(0);
      ds.setConnectionTimeout(1500);
      Flyway.configure()
          .dataSource(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword())
          .schemas("outbox_metering")
          .load()
          .migrate();
      return ds;
    }

    @Bean
    JdbcTemplate jdbc(DataSource ds) {
      return new JdbcTemplate(ds);
    }

    @Bean
    PlatformTransactionManager transactions(DataSource ds) {
      return new DataSourceTransactionManager(ds);
    }

    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    EventPublisher transport() {
      return mock(EventPublisher.class);
    }

    @Bean
    OutboxRepository repository(JdbcTemplate db, ObjectMapper mapper) {
      return new OutboxRepository(db, mapper, "outbox_metering");
    }

    @Bean
    OutboxBatchPublisher dispatcher(OutboxRepository repository, EventPublisher transport) {
      return new OutboxBatchPublisher(repository, transport);
    }
  }

  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper mapper;
  @Autowired OutboxRepository repository;
  @Autowired OutboxBatchPublisher dispatcher;
  @Autowired EventPublisher transport;

  @AfterAll
  static void stopDatabase() {
    if (POSTGRES.isRunning()) POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    db.execute(
        "TRUNCATE"
            + " outbox_metering.outbox_events,outbox_metering.contracts,outbox_metering.usage_policies"
            + " CASCADE");
    reset(transport);
  }

  DomainEvent enqueue() {
    var event =
        new DomainEvent(
            UUID.randomUUID(),
            1,
            "TestEvent",
            Instant.now(),
            UUID.randomUUID(),
            "alice",
            Map.of("value", 1));
    repository.insert(event);
    return event;
  }

  @Test
  void slowBrokerDoesNotHoldTheOnlyConnectionOrAllowAnOverlappingPublish() throws Exception {
    var event = enqueue();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              entered.countDown();
              assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(transport)
        .publish(any());
    var worker = CompletableFuture.runAsync(dispatcher::publishPending);
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(db.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
      dispatcher.publishPending();
      verify(transport, times(1)).publish(event);
    } finally {
      release.countDown();
    }
    worker.get(8, TimeUnit.SECONDS);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_metering.outbox_events WHERE published_at IS NOT NULL",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void expiredClaimIsRecoveredAndOldOwnerCannotAcknowledgeOrReleaseIt() {
    enqueue();
    var old = repository.claim().orElseThrow();
    assertThat(repository.claim()).isEmpty();
    db.update("UPDATE outbox_metering.outbox_events SET claim_until=now()-interval '1 second'");
    var current = repository.claim().orElseThrow();
    assertThat(current.event()).isEqualTo(old.event());
    assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
    repository.published(old);
    repository.failed(old);
    assertThat(
            db.queryForObject("SELECT claim_token FROM outbox_metering.outbox_events", UUID.class))
        .isEqualTo(current.claimToken());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_metering.outbox_events WHERE published_at IS NOT NULL",
                Long.class))
        .isZero();
    repository.published(current);
    assertThat(
            db.queryForObject("SELECT attempts FROM outbox_metering.outbox_events", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void failedDeliveryBacksOffAndRetriesTheSameEvent() {
    var event = enqueue();
    doThrow(new IllegalStateException("broker unavailable")).when(transport).publish(any());
    dispatcher.publishPending();
    assertThat(repository.claim()).isEmpty();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_metering.outbox_events WHERE claim_token IS NULL AND"
                    + " published_at IS NULL AND attempts=1 AND next_attempt_at>now()",
                Long.class))
        .isEqualTo(1);
    reset(transport);
    db.update("UPDATE outbox_metering.outbox_events SET next_attempt_at=now()");
    dispatcher.publishPending();
    verify(transport).publish(event);
    assertThat(
            db.queryForObject("SELECT attempts FROM outbox_metering.outbox_events", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void crashAfterPublishMayRedeliverTheSameStableEventId() {
    var event = enqueue();
    var abandoned = repository.claim().orElseThrow();
    transport.publish(abandoned.event());
    db.update("UPDATE outbox_metering.outbox_events SET claim_until=now()-interval '1 second'");
    dispatcher.publishPending();
    verify(transport, times(2)).publish(event);
  }

  @Test
  void eachDispatchHasABoundedBatch() {
    for (int i = 0; i < 7; i++) enqueue();
    dispatcher.publishPending();
    verify(transport, times(5)).publish(any());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_metering.outbox_events WHERE published_at IS NULL",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  void upgradePreservesExistingPublishedAndPendingEvents() {
    var schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var jdbcUrl = POSTGRES.getJdbcUrl();
    var username = POSTGRES.getUsername();
    var password = POSTGRES.getPassword();
    Flyway.configure()
        .dataSource(jdbcUrl, username, password)
        .schemas(schema)
        .target("2")
        .load()
        .migrate();
    db.update(
        "INSERT INTO "
            + schema
            + ".outbox_events(event_id,aggregate_id,event_type,payload) VALUES(?,?,?,?::jsonb)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "BeforeUpgrade",
        "{}");
    db.update(
        "INSERT INTO "
            + schema
            + ".outbox_events(event_id,aggregate_id,event_type,payload,published_at,attempts)"
            + " VALUES(?,?,?,?::jsonb,now(),4)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "PublishedBeforeUpgrade",
        "{}");
    Flyway.configure().dataSource(jdbcUrl, username, password).schemas(schema).load().migrate();
    assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".outbox_events", Long.class))
        .isEqualTo(2);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".outbox_events WHERE published_at IS NOT NULL AND attempts=4",
                Long.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".outbox_events WHERE claim_token IS NULL AND claim_until IS NULL",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  void corruptPayloadRollsBackTheClaim() {
    enqueue();
    db.update("UPDATE outbox_metering.outbox_events SET payload='{}'::jsonb");
    // Invalid JSON shape must not silently acknowledge an event.
    db.update(
        "UPDATE outbox_metering.outbox_events SET"
            + " payload=jsonb_build_object('schemaVersion','invalid')");
    assertThatThrownBy(repository::claim).isInstanceOf(RuntimeException.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_metering.outbox_events WHERE attempts=0 AND"
                    + " claim_token IS NULL",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void meteringAdaptersRoundTripThroughConfiguredNonDefaultSchema() {
    var policies =
        new lt.printerrental.metering.persistence.JdbcUsagePolicyRepository(db, "outbox_metering");
    policies.save(
        "MODEL",
        "test-model",
        java.math.BigDecimal.ZERO,
        new java.math.BigDecimal("100"),
        0,
        "employee");
    assertThat(policies.find("MODEL", "test-model").orElseThrow().maximumPerDay())
        .isEqualByComparingTo("100");
    var readings =
        new lt.printerrental.metering.persistence.JdbcReadingRepository(
            db, mapper, "outbox_metering");
    var contract = UUID.randomUUID();
    db.update(
        "INSERT INTO outbox_metering.contracts(contract_id,owner_subject,source_revision,snapshot)"
            + " VALUES(?,?,1,\'{}\'::jsonb)",
        contract,
        "alice");
    assertThat(readings.reminder(contract, java.time.LocalDate.of(2026, 1, 1), "MISSING")).isTrue();
    assertThat(readings.reminder(contract, java.time.LocalDate.of(2026, 1, 1), "MISSING"))
        .isFalse();
    assertThat(readings.history(contract, 0, 10)).isEmpty();
    var date = java.time.LocalDate.of(2026, 1, 31);
    var reading =
        new lt.printerrental.metering.application.MeterReading(
            UUID.randomUUID(),
            contract,
            date,
            List.of(10L, 0L, 0L, 0L),
            "PENDING",
            null,
            UUID.randomUUID(),
            "alice",
            List.of(),
            Map.of(),
            Instant.parse("2026-02-01T00:00:00Z"),
            null);
    readings.insert(reading);
    assertThat(readings.byRequest(contract, reading.requestKey()).orElseThrow().id())
        .isEqualTo(reading.id());
    assertThat(readings.find(reading.id())).isPresent();
    readings.review(reading.id(), "ACCEPTED", "employee", "Checked", Instant.now());
    assertThat(readings.onDate(contract, date)).isPresent();
    assertThat(readings.previous(contract, date.plusDays(1))).isPresent();
    assertThat(readings.next(contract, date.minusDays(1))).isPresent();
    readings.supersede(reading.id());
    assertThat(readings.onDate(contract, date)).isEmpty();
    assertThat(readings.history(contract, 0, 10)).hasSize(1);
  }
}
