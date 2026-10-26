package lt.printerrental.auth;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class InternalSecurityConfiguration {
  @Bean
  @Order(0)
  SecurityFilterChain internalSecurity(
      HttpSecurity http,
      @Value("${INTERNAL_AUTH_MODE:deny}") String mode,
      @Value("${INTERNAL_AUDIENCE:}") String audience,
      @Value("${INTERNAL_CALLER_EMAILS:}") String emails,
      @Value("${PUBSUB_EMULATOR_HOST:}") String emulator)
      throws Exception {
    http.securityMatcher("/internal/**")
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (mode.equals("emulator")) {
      if (System.getenv("K_SERVICE") != null || emulator.isBlank())
        throw new IllegalStateException(
            "Unauthenticated internal endpoints require a local Pub/Sub emulator");
      http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
    } else if (mode.equals("google")) {
      if (audience.isBlank() || emails.isBlank())
        throw new IllegalStateException("Internal Google audience and callers are required");
      var allowed = Set.copyOf(Arrays.stream(emails.split(",")).map(String::strip).toList());
      var decoder =
          NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer("https://accounts.google.com"),
              jwt -> {
                boolean valid =
                    jwt.getAudience().contains(audience)
                        && allowed.contains(jwt.getClaimAsString("email"))
                        && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
                return valid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
              }));
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
    } else http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
    return http.build();
  }
}
