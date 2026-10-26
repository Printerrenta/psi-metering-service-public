package lt.printerrental.neon;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Translate Neon's PostgreSQL URLs without embedding passwords in JDBC URLs or logs. */
public class NeonEnvironmentPostProcessor implements EnvironmentPostProcessor {
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.containsProperty("DATABASE_URL") || environment.containsProperty("DB_URL")) {
      return; // Preserve the explicit Docker/local connection configuration.
    }
    var pooled = parse(environment.getProperty("DATABASE_URL"), "DATABASE_URL");
    var direct = parse(environment.getProperty("DATABASE_URL_UNPOOLED"), "DATABASE_URL_UNPOOLED");
    if (URI.create(direct.jdbcUrl().substring(5)).getHost().contains("-pooler")) {
      throw new IllegalArgumentException(
          "DATABASE_URL_UNPOOLED must use a direct endpoint for Flyway");
    }
    Map<String, Object> values = new HashMap<>();
    values.put("DB_URL", withSchema(pooled.jdbcUrl(), environment.getProperty("DB_SCHEMA")));
    values.put("DB_USERNAME", pooled.username());
    values.put("DB_PASSWORD", pooled.password());
    values.put(
        "DB_MIGRATION_URL", withSchema(direct.jdbcUrl(), environment.getProperty("DB_SCHEMA")));
    values.put("DB_MIGRATION_USERNAME", direct.username());
    values.put("DB_MIGRATION_PASSWORD", direct.password());
    environment.getPropertySources().addLast(new MapPropertySource("neonJdbcDefaults", values));
  }

  private String withSchema(String url, String schema) {
    if (schema == null || schema.isBlank()) return url;
    if (!schema.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("Invalid service schema");
    return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  static Connection parse(String value, String key) {
    try {
      var uri = URI.create(value);
      if (!("postgres".equals(uri.getScheme()) || "postgresql".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() == null
          || uri.getRawPath() == null
          || uri.getRawPath().length() < 2
          || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
      var credentials = uri.getRawUserInfo().split(":", 2);
      if (credentials.length != 2 || credentials[0].isEmpty() || credentials[1].isEmpty()) {
        throw new IllegalArgumentException();
      }
      var query = uri.getRawQuery();
      if (uri.getHost().endsWith(".neon.tech")) {
        query =
            query == null
                ? "sslmode=verify-full"
                : query.replaceAll("(^|&)sslmode=[^&]*", "$1sslmode=verify-full");
        if (!query.contains("sslmode=")) query += "&sslmode=verify-full";
        if (!query.contains("sslfactory="))
          query += "&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory";
      }
      // Neon URLs use libpq's name; pgJDBC uses camelCase.
      if (query != null) {
        query = query.replaceAll("(^|&)channel_binding=", "$1channelBinding=");
        for (var parameter : query.split("&")) {
          var name = decode(parameter.split("=", 2)[0]);
          if ("user".equalsIgnoreCase(name) || "password".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException();
          }
        }
      }
      var host =
          uri.getHost().contains(":") && !uri.getHost().startsWith("[")
              ? "[" + uri.getHost() + "]"
              : uri.getHost();
      var jdbc =
          "jdbc:postgresql://"
              + host
              + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
              + uri.getRawPath()
              + (query == null ? "" : "?" + query);
      return new Connection(jdbc, decode(credentials[0]), decode(credentials[1]));
    } catch (RuntimeException error) {
      // URI parse errors can include the supplied password. Never retain their messages/causes.
      throw new IllegalArgumentException(
          key + " must be a PostgreSQL URL with host, database and credentials");
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  record Connection(String jdbcUrl, String username, String password) {
    @Override
    public String toString() {
      return "Connection[credentials redacted]";
    }
  }
}
