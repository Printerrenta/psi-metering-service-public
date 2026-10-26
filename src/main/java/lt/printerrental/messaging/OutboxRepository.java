package lt.printerrental.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lt.printerrental.events.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
  public record Pending(DomainEvent event, int attempts, UUID claimToken) {}

  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final String table;

  public OutboxRepository(
      JdbcTemplate db, ObjectMapper mapper, @Value("${DB_SCHEMA:metering}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
    this.db = db;
    this.mapper = mapper;
    this.table = schema + ".outbox_events";
  }

  public void insert(DomainEvent event) {
    try {
      db.update(
          "INSERT INTO "
              + table
              + "(event_id,aggregate_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
          event.eventId(),
          event.aggregateId(),
          event.type(),
          mapper.writeValueAsString(event));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("Event could not be encoded", error);
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Pending> claim() {
    var token = UUID.randomUUID();
    return db
        .query(
            "WITH candidate AS (SELECT event_id FROM "
                + table
                + " WHERE published_at IS NULL AND next_attempt_at<=now()"
                + " AND (claim_until IS NULL OR claim_until<=now())"
                + " ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                + " UPDATE "
                + table
                + " AS event SET claim_token=?,"
                + " claim_until=now()+interval '60 seconds',attempts=event.attempts+1"
                + " FROM candidate WHERE event.event_id=candidate.event_id"
                + " RETURNING event.payload,event.attempts",
            (rs, row) -> {
              try {
                return new Pending(
                    mapper.readValue(rs.getString(1), DomainEvent.class), rs.getInt(2), token);
              } catch (Exception error) {
                throw new java.sql.SQLException("Invalid stored event", error);
              }
            },
            token)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void published(Pending row) {
    db.update(
        "UPDATE "
            + table
            + " SET published_at=now(),claim_token=NULL,claim_until=NULL"
            + " WHERE event_id=? AND claim_token=? AND published_at IS NULL",
        row.event().eventId(),
        row.claimToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(Pending row) {
    long seconds = Math.min(300, 1L << Math.min(row.attempts(), 8));
    db.update(
        "UPDATE "
            + table
            + " SET next_attempt_at=now()+(? * interval '1 second'),"
            + " claim_token=NULL,claim_until=NULL"
            + " WHERE event_id=? AND claim_token=? AND published_at IS NULL",
        seconds,
        row.event().eventId(),
        row.claimToken());
  }
}
