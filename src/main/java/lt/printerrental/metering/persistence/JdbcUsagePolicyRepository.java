package lt.printerrental.metering.persistence;

import java.math.BigDecimal;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.metering.application.UsagePolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUsagePolicyRepository implements UsagePolicyRepository {
  private final JdbcTemplate db;
  private final String schema;

  public JdbcUsagePolicyRepository(JdbcTemplate db, @Value("${DB_SCHEMA:metering}") String schema) {
    if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema");
    this.schema = schema;
    this.db = db;
  }

  public Optional<Policy> find(String scope, String key) {
    return db
        .query(
            "SELECT * FROM " + schema + ".usage_policies WHERE scope=? AND policy_key=?",
            (r, n) ->
                new Policy(
                    r.getString("scope"),
                    r.getString("policy_key"),
                    r.getBigDecimal("minimum_per_day"),
                    r.getBigDecimal("maximum_per_day"),
                    r.getLong("version")),
            scope,
            key)
        .stream()
        .findFirst();
  }

  public Policy save(
      String scope, String key, BigDecimal min, BigDecimal max, long version, String actor) {
    if (version == 0) {
      db.update(
          "INSERT INTO"
              + " "
              + schema
              + ".usage_policies(scope,policy_key,minimum_per_day,maximum_per_day,version,actor)"
              + " VALUES(?,?,?,?,1,?)",
          scope,
          key,
          min,
          max,
          actor);
    } else if (db.update(
            "UPDATE "
                + schema
                + ".usage_policies SET"
                + " minimum_per_day=?,maximum_per_day=?,version=version+1,actor=?,updated_at=now()"
                + " WHERE scope=? AND policy_key=? AND version=?",
            min,
            max,
            actor,
            scope,
            key,
            version)
        != 1) throw new ApiProblem(409, "Usage policy changed");
    return find(scope, key).orElseThrow();
  }
}
