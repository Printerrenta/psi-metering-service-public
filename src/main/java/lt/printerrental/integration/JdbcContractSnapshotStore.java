package lt.printerrental.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcContractSnapshotStore implements ContractSnapshotStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String table;

  public JdbcContractSnapshotStore(
      JdbcTemplate db, ObjectMapper json, @Value("${DB_SCHEMA}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
    this.db = db;
    this.json = json;
    this.table = schema + ".contracts";
  }

  public Optional<ContractSnapshot> find(UUID id) {
    return db
        .query(
            "SELECT snapshot FROM " + table + " WHERE contract_id=?",
            (r, n) -> decode(r.getString(1)),
            id)
        .stream()
        .findFirst();
  }

  public Locked lock(UUID id) {
    return db
        .query(
            "SELECT snapshot,version FROM " + table + " WHERE contract_id=? FOR UPDATE",
            (r, n) -> new Locked(decode(r.getString(1)), r.getLong(2)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiProblem(404, "Contract projection is not initialized"));
  }

  public boolean receive(ContractSnapshot s) {
    validate(s);
    return db.update(
            "INSERT INTO "
                + table
                + "(contract_id,owner_subject,source_revision,snapshot) VALUES(?,?,?,?::jsonb) ON"
                + " CONFLICT(contract_id) DO UPDATE SET"
                + " source_revision=EXCLUDED.source_revision,snapshot=EXCLUDED.snapshot,version="
                + table
                + ".version+1 WHERE "
                + table
                + ".source_revision<EXCLUDED.source_revision AND "
                + table
                + ".owner_subject=EXCLUDED.owner_subject",
            s.contractId(),
            s.ownerSubject(),
            s.revision(),
            encode(s))
        > 0;
  }

  public long bump(UUID id) {
    return db.queryForObject(
        "UPDATE " + table + " SET version=version+1 WHERE contract_id=? RETURNING version",
        Long.class,
        id);
  }

  public long version(UUID id) {
    return db.queryForObject(
        "SELECT version FROM " + table + " WHERE contract_id=?", Long.class, id);
  }

  public List<UUID> ids() {
    return db.query(
        "SELECT contract_id FROM " + table + " ORDER BY contract_id",
        (r, n) -> r.getObject(1, UUID.class));
  }

  private ContractSnapshot decode(String text) {
    try {
      return json.readValue(text, ContractSnapshot.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored contract snapshot");
    }
  }

  private String encode(ContractSnapshot s) {
    try {
      return json.writeValueAsString(s);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid contract snapshot");
    }
  }

  private void validate(ContractSnapshot s) {
    if (s.contractId() == null
        || s.printerId() == null
        || s.ownerSubject() == null
        || s.ownerSubject().isBlank()
        || s.ownerSubject().length() > 255
        || s.revision() < 1
        || s.baselineDate() == null
        || !s.baselineDate().equals(s.startDate())
        || s.startDate().isAfter(s.endDate())
        || s.baseline().size() != 4
        || s.baseline().stream().anyMatch(n -> n == null || n < 0)
        || s.terms().isEmpty()
        || !Set.of("DRAFT", "ACTIVE", "COMPLETED").contains(s.status()))
      throw new IllegalArgumentException("Invalid contract snapshot");
  }
}
