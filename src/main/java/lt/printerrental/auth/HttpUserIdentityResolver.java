package lt.printerrental.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import lt.printerrental.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "'${IDENTITY_SERVICE_URL:}' != ''")
public class HttpUserIdentityResolver implements UserIdentityResolver {
  private static final Logger LOG = LoggerFactory.getLogger(HttpUserIdentityResolver.class);
  private final String url;
  private final ServiceHttpClient client;
  private final ObjectMapper mapper;

  public HttpUserIdentityResolver(
      @Value("${IDENTITY_SERVICE_URL}") String url, ServiceHttpClient client, ObjectMapper mapper) {
    this.url = url;
    this.client = client;
    this.mapper = mapper;
  }

  public AppUser resolve(Jwt jwt) {
    HttpResponse<byte[]> response;
    try {
      response =
          client.send(url, "/api/auth/me", "GET", null, null, "Bearer " + jwt.getTokenValue());
    } catch (UpstreamUnavailableException error) {
      LOG.warn("Identity service request failed", error);
      throw new AuthenticationServiceException("Identity service unavailable", error);
    }
    if (response.statusCode() == 401 || response.statusCode() == 403)
      throw new InvalidBearerTokenException("Identity rejected");
    if (response.statusCode() != 200) {
      LOG.warn("Identity service returned unexpected HTTP status {}", response.statusCode());
      throw new AuthenticationServiceException("Identity service unavailable");
    }
    try {
      return mapper.readValue(response.body(), AppUser.class);
    } catch (Exception error) {
      LOG.warn("Failed to parse identity service response", error);
      throw new AuthenticationServiceException("Invalid identity response", error);
    }
  }
}
