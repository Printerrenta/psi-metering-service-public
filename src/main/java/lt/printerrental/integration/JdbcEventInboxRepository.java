package lt.printerrental.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lt.printerrental.events.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEventInboxRepository implements EventInboxRepository {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String table;

  public JdbcEventInboxRepository(
      JdbcTemplate db, ObjectMapper json, @Value("${DB_SCHEMA}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
    this.db = db;
    this.json = json;
    table = schema + ".inbox_events";
  }

  public boolean insert(DomainEvent event) {
    try {
      return db.update(
              "INSERT INTO "
                  + table
                  + "(event_id,payload) VALUES(?,?::jsonb) ON CONFLICT DO NOTHING",
              event.eventId(),
              json.writeValueAsString(event))
          > 0;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid event");
    }
  }

  public void processed(UUID id) {
    db.update("UPDATE " + table + " SET processed_at=now() WHERE event_id=?", id);
  }

  public List<DomainEvent> pending() {
    return db.query(
        "SELECT payload FROM "
            + table
            + " WHERE processed_at IS NULL ORDER BY received_at,event_id LIMIT 50 FOR UPDATE SKIP"
            + " LOCKED",
        (r, n) -> {
          try {
            return json.readValue(r.getString(1), DomainEvent.class);
          } catch (Exception e) {
            throw new java.sql.SQLException("Invalid inbox event", e);
          }
        });
  }
}
