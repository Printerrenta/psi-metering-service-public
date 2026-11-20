package lt.printerrental.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtException;

public final class RemoteJwksSigningKeyProvider implements SigningKeyProvider {
  private static final Logger LOG = LoggerFactory.getLogger(RemoteJwksSigningKeyProvider.class);
  private final URI uri;
  private final HttpClient http;
  private final Clock clock;
  private JWKSet keys;
  private Instant refreshed = Instant.EPOCH;
  private Instant lastAttempt = Instant.EPOCH;

  public RemoteJwksSigningKeyProvider(URI uri, HttpClient http, Clock clock) {
    this.uri = uri;
    this.http = http;
    this.clock = clock;
  }

  @Override
  public synchronized JWK findById(String kid) {
    var now = clock.instant();
    boolean expired = refreshed.plusSeconds(300).isBefore(now);
    boolean missingKey = keys != null && keys.getKeyByKeyId(kid) == null;
    if (keys == null || ((expired || missingKey) && lastAttempt.plusSeconds(10).isBefore(now))) {
      refresh(now);
    }
    return keys.getKeyByKeyId(kid);
  }

  private void refresh(Instant now) {
    lastAttempt = now;
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || response.body().length() > 65536) {
        throw new JwtException("JWKS unavailable");
      }
      keys = JWKSet.parse(response.body());
      refreshed = now;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      if (keys != null) {
        LOG.warn("JWKS refresh interrupted for {}; using cached signing keys", uri, error);
        return;
      }
      LOG.warn("JWKS refresh interrupted for {} with no cached keys", uri, error);
      throw new JwtException("Signing keys unavailable", error);
    } catch (Exception error) {
      if (keys != null) {
        LOG.warn("Failed to refresh JWKS from {}; using cached signing keys", uri, error);
        return;
      }
      LOG.warn("Failed to load initial JWKS from {}", uri, error);
      if (error instanceof JwtException jwtError) throw jwtError;
      throw new JwtException("Signing keys unavailable", error);
    }
  }
}
