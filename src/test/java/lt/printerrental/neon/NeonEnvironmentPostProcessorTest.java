package lt.printerrental.neon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class NeonEnvironmentPostProcessorTest {
  private final NeonEnvironmentPostProcessor processor = new NeonEnvironmentPostProcessor();

  @Test
  void translatesPooledAndDirectUrlsAndKeepsCredentialsOutOfJdbcUrl() {
    var environment =
        new MockEnvironment()
            .withProperty(
                "DATABASE_URL",
                "postgresql://user:p%40ss+word@ep-demo-pooler.neon.tech/neondb?sslmode=require&channel_binding=require")
            .withProperty(
                "DATABASE_URL_UNPOOLED",
                "postgres://user:p%40ss+word@ep-demo.neon.tech/neondb?sslmode=require");
    processor.postProcessEnvironment(environment, new SpringApplication());
    assertThat(environment.getProperty("DB_URL"))
        .isEqualTo(
            "jdbc:postgresql://ep-demo-pooler.neon.tech/neondb?sslmode=verify-full&channelBinding=require&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory");
    assertThat(environment.getProperty("DB_PASSWORD")).isEqualTo("p@ss+word");
    assertThat(environment.getProperty("DB_MIGRATION_URL"))
        .isEqualTo(
            "jdbc:postgresql://ep-demo.neon.tech/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory");
  }

  @Test
  void preservesExplicitLocalDatabaseConfiguration() {
    var environment =
        new MockEnvironment()
            .withProperty("DATABASE_URL", "not-used")
            .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/rental");
    processor.postProcessEnvironment(environment, new SpringApplication());
    assertThat(environment.getProperty("DB_URL"))
        .isEqualTo("jdbc:postgresql://localhost:5432/rental");
    assertThat(environment.getProperty("DB_MIGRATION_URL")).isNull();
  }

  @Test
  void rejectsMissingOrPooledMigrationConnection() {
    var environment =
        new MockEnvironment()
            .withProperty("DATABASE_URL", "postgres://user:pass@ep-pooler.neon.tech/neondb");
    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
        .hasMessageContaining("DATABASE_URL_UNPOOLED");
    environment.withProperty(
        "DATABASE_URL_UNPOOLED", "postgres://user:pass@ep-pooler.neon.tech/neondb");
    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
        .hasMessageContaining("direct endpoint");
  }

  @Test
  void malformedUrlDoesNotLeakCredentialsIntoException() {
    assertThatThrownBy(
            () ->
                NeonEnvironmentPostProcessor.parse(
                    "postgres://user:secret password@host/db", "DATABASE_URL"))
        .hasMessageNotContaining("secret")
        .hasCause(null);
  }
}
