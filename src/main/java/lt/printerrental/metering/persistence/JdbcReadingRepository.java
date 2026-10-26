package lt.printerrental.metering.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import lt.printerrental.metering.application.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReadingRepository implements ReadingRepository {
  private final JdbcTemplate db;
  private final String schema;
  private final ObjectMapper json;

  public JdbcReadingRepository(
      JdbcTemplate db, ObjectMapper json, @Value("${DB_SCHEMA:metering}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
    this.schema = schema;
    this.db = db;
    this.json = json;
  }

  public Optional<MeterReading> find(UUID id) {
    return query("id=?", id).stream().findFirst();
  }

  public Optional<MeterReading> byRequest(UUID contract, UUID key) {
    return query("contract_id=? AND request_key=?", contract, key).stream().findFirst();
  }

  public Optional<MeterReading> onDate(UUID contract, LocalDate date) {
    return query("contract_id=? AND as_of=? AND status='ACCEPTED'", contract, date).stream()
        .findFirst();
  }

  public Optional<MeterReading> previous(UUID contract, LocalDate date) {
    return query(
            "contract_id=? AND as_of<? AND status='ACCEPTED' ORDER BY as_of DESC LIMIT 1",
            contract,
            date)
        .stream()
        .findFirst();
  }

  public Optional<MeterReading> next(UUID contract, LocalDate date) {
    return query(
            "contract_id=? AND as_of>? AND status='ACCEPTED' ORDER BY as_of LIMIT 1",
            contract,
            date)
        .stream()
        .findFirst();
  }

  public List<MeterReading> history(UUID contract, int page, int size) {
    return query(
        "contract_id=? ORDER BY as_of DESC,submitted_at DESC,id LIMIT ? OFFSET ?",
        contract,
        size,
        Math.multiplyExact(page, size));
  }

  public List<MeterReading> pending(int page, int size) {
    return query(
        "status='PENDING' ORDER BY submitted_at,id LIMIT ? OFFSET ?",
        size,
        Math.multiplyExact(page, size));
  }

  public void insert(MeterReading r) {
    db.update(
        "INSERT INTO"
            + " "
            + schema
            + ".readings(id,contract_id,as_of,counters,status,supersedes,request_key,submitted_by,warnings,validation,submitted_at)"
            + " VALUES(?,?,?,?::jsonb,?,?,?,?,?::jsonb,?::jsonb,?)",
        r.id(),
        r.contractId(),
        r.asOf(),
        encode(r.counters()),
        r.status(),
        r.supersedes(),
        r.requestKey(),
        r.submittedBy(),
        encode(r.warnings()),
        encode(r.validation()),
        java.sql.Timestamp.from(r.submittedAt()));
  }

  public void supersede(UUID id) {
    db.update(
        "UPDATE " + schema + ".readings SET status='SUPERSEDED' WHERE id=? AND status='ACCEPTED'",
        id);
  }

  public void review(UUID id, String status, String actor, String reason, Instant now) {
    db.update(
        "UPDATE "
            + schema
            + ".readings SET status=?,reviewed_by=?,review_reason=?,reviewed_at=? WHERE"
            + " id=?",
        status,
        actor,
        reason,
        java.sql.Timestamp.from(now),
        id);
  }

  public boolean reminder(UUID contract, LocalDate month, String kind) {
    return db.update(
            "INSERT INTO "
                + schema
                + ".reminders(contract_id,period,kind) VALUES(?,?,?) ON CONFLICT DO"
                + " NOTHING",
            contract,
            month,
            kind)
        > 0;
  }

  private List<MeterReading> query(String where, Object... args) {
    return db.query(
        "SELECT * FROM " + schema + ".readings WHERE " + where,
        (rs, n) -> {
          try {
            return new MeterReading(
                rs.getObject("id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                rs.getObject("as_of", LocalDate.class),
                json.readValue(rs.getString("counters"), new TypeReference<List<Long>>() {}),
                rs.getString("status"),
                rs.getObject("supersedes", UUID.class),
                rs.getObject("request_key", UUID.class),
                rs.getString("submitted_by"),
                json.readValue(rs.getString("warnings"), new TypeReference<List<String>>() {}),
                json.readValue(
                    rs.getString("validation"), new TypeReference<Map<String, Object>>() {}),
                rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("review_reason"));
          } catch (Exception e) {
            throw new java.sql.SQLException("Invalid stored reading", e);
          }
        },
        args);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid reading");
    }
  }
}
