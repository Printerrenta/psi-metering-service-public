package lt.printerrental.auth;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

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
    var normalizedMode = mode == null ? "" : mode.strip();
    if ("deny".equals(normalizedMode)) {
      http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
    } else if ("emulator".equals(normalizedMode)) {
      if (System.getenv("K_SERVICE") != null || emulator == null || emulator.isBlank())
        throw new IllegalStateException(
            "Unauthenticated internal endpoints require a local Pub/Sub emulator");
      http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
    } else if ("google".equals(normalizedMode)) {
      var normalizedAudience = audience == null ? "" : audience.strip();
      var allowed =
          Set.copyOf(
              Arrays.stream((emails == null ? "" : emails).split(","))
                  .map(String::strip)
                  .filter(s -> !s.isEmpty())
                  .toList());
      if (normalizedAudience.isEmpty() || allowed.isEmpty())
        throw new IllegalStateException("Internal Google audience and callers are required");
      var requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(Duration.ofSeconds(5));
      requestFactory.setReadTimeout(Duration.ofSeconds(10));
      var decoder =
          NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
              .restOperations(new RestTemplate(requestFactory))
              .build();
      decoder.setJwtValidator(googleValidator(normalizedAudience, allowed));
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
    } else {
      throw new IllegalStateException("Unsupported INTERNAL_AUTH_MODE: " + normalizedMode);
    }
    return http.build();
  }

  static OAuth2TokenValidator<Jwt> googleValidator(String audience, Set<String> allowed) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("https://accounts.google.com"),
        jwt -> {
          var tokenAudience = jwt.getAudience();
          var email = jwt.getClaimAsString("email");
          boolean valid =
              tokenAudience != null
                  && tokenAudience.contains(audience)
                  && email != null
                  && allowed.contains(email)
                  && Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
  }
}
