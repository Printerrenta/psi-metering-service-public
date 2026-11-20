package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;

class InternalSecurityConfigurationTest {
  private final InternalSecurityConfiguration config = new InternalSecurityConfiguration();

  @Test
  void failsFastOnUnsupportedInternalAuthMode() {
    var http = mock(HttpSecurity.class, Answers.RETURNS_SELF);
    assertThatThrownBy(() -> config.internalSecurity(http, "googl", "", "", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported INTERNAL_AUTH_MODE");
  }

  @Test
  void rejectsGoogleModeWithBlankOnlyCallerEmails() {
    var http = mock(HttpSecurity.class, Answers.RETURNS_SELF);
    assertThatThrownBy(
            () -> config.internalSecurity(http, "google", "https://metering.test", " ,  , ", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Internal Google audience and callers are required");
  }

  @Test
  void rejectsEmulatorModeWithoutEmulatorHost() {
    var http = mock(HttpSecurity.class, Answers.RETURNS_SELF);
    assertThatThrownBy(() -> config.internalSecurity(http, "emulator", "", "", "  "))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void googleValidatorHandlesMissingAudienceAndEmailWithoutNpe() {
    var validator =
        InternalSecurityConfiguration.googleValidator(
            "https://metering.test", Set.of("pubsub@project.iam.gserviceaccount.com"));
    var now = Instant.now();

    var noAudience =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .issuer("https://accounts.google.com")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("email", "pubsub@project.iam.gserviceaccount.com")
            .claim("email_verified", true)
            .build();
    assertThat(validator.validate(noAudience).hasErrors()).isTrue();

    var noEmail =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .issuer("https://accounts.google.com")
            .audience(List.of("https://metering.test"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("email_verified", true)
            .build();
    assertThat(validator.validate(noEmail).hasErrors()).isTrue();

    var valid =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .issuer("https://accounts.google.com")
            .audience(List.of("https://metering.test"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("email", "pubsub@project.iam.gserviceaccount.com")
            .claim("email_verified", true)
            .build();
    assertThat(validator.validate(valid).hasErrors()).isFalse();
  }
}
