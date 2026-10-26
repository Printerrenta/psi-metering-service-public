package lt.printerrental.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lt.printerrental.transport.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "'${IDENTITY_SERVICE_URL:}' != ''")
public class HttpUserIdentityResolver implements UserIdentityResolver {
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
    var response =
        client.send(url, "/api/auth/me", "GET", null, null, "Bearer " + jwt.getTokenValue());
    if (response.statusCode() == 401 || response.statusCode() == 403)
      throw new InvalidBearerTokenException("Identity rejected");
    if (response.statusCode() != 200)
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "Identity service unavailable");
    try {
      return mapper.readValue(response.body(), AppUser.class);
    } catch (Exception error) {
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "Invalid identity response");
    }
  }
}
