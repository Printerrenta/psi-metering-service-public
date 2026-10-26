package lt.printerrental.testing;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;

/** Docker by default; an explicit loopback URL creates a disposable DB per test class. */
public final class TestPostgresContainer extends PostgreSQLContainer<TestPostgresContainer> {
  private final String local = System.getenv("SERVICE_TEST_JDBC_URL");
  private String database;
  private boolean localRunning;

  public TestPostgresContainer(String image) {
    super(image);
    if (local != null && !local.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"))
      throw new IllegalArgumentException(
          "Test DB must use 127.0.0.1, an explicit port and postgres");
  }

  @Override
  public void start() {
    if (local == null) {
      super.start();
      return;
    }
    if (localRunning) return;
    database = "service_test_" + UUID.randomUUID().toString().replace("-", "");
    execute("CREATE DATABASE " + database);
    localRunning = true;
  }

  @Override
  public void stop() {
    if (local == null) {
      super.stop();
      return;
    }
    if (!localRunning) return;
    // The name is generated here, never accepted from configuration or request data.
    execute("DROP DATABASE " + database + " WITH (FORCE)");
    localRunning = false;
  }

  @Override
  public boolean isRunning() {
    return local == null ? super.isRunning() : localRunning;
  }

  @Override
  public String getJdbcUrl() {
    if (local == null) return super.getJdbcUrl();
    if (!localRunning) start();
    return local.substring(0, local.lastIndexOf('/') + 1) + database;
  }

  @Override
  public String getUsername() {
    return local == null ? super.getUsername() : "postgres";
  }

  @Override
  public String getPassword() {
    return local == null ? super.getPassword() : "";
  }

  private void execute(String sql) {
    try (var connection = DriverManager.getConnection(local, "postgres", "");
        var statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException error) {
      throw new IllegalStateException("Disposable local test DB failed", error);
    }
  }
}
