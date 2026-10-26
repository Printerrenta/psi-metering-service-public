package lt.printerrental.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MetadataServiceCredentials implements ServiceCredentials {
  private static final String BASE =
      "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/";
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper mapper;
  private final Map<String, Token> cache = new HashMap<>();

  record Token(String value, Instant expires) {}

  public MetadataServiceCredentials(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public synchronized String identityToken(String audience) {
    return token(
        audience,
        "identity?audience=" + URLEncoder.encode(audience, StandardCharsets.UTF_8) + "&format=full",
        false);
  }

  public synchronized String accessToken() {
    return token("access", "token", true);
  }

  private String token(String key, String path, boolean access) {
    if (System.getenv("K_SERVICE") == null)
      return null; // Local Docker network; never fabricate a cloud credential.
    var cached = cache.get(key);
    if (cached != null && cached.expires().isAfter(Instant.now().plusSeconds(60)))
      return cached.value();
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(BASE + path))
                  .header("Metadata-Flavor", "Google")
                  .timeout(Duration.ofSeconds(5))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("Service credentials unavailable");
      String value;
      Instant expiry;
      if (access) {
        var data = mapper.readTree(response.body());
        value = data.path("access_token").asText();
        expiry = Instant.now().plusSeconds(data.path("expires_in").asLong());
      } else {
        value = response.body();
        var payload = mapper.readTree(Base64.getUrlDecoder().decode(value.split("\\.")[1]));
        expiry = Instant.ofEpochSecond(payload.path("exp").asLong());
      }
      cache.put(key, new Token(value, expiry));
      return value;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Service credentials unavailable");
    } catch (Exception error) {
      throw new IllegalStateException("Service credentials unavailable");
    }
  }
}
