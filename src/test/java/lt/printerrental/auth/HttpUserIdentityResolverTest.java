package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import lt.printerrental.transport.ServiceHttpClient;
import lt.printerrental.transport.UpstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class HttpUserIdentityResolverTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private Jwt jwt() {
    return Jwt.withTokenValue("provider-token").header("alg", "EdDSA").subject("alice").build();
  }

  @Test
  void wrapsUpstreamUnavailableInAuthenticationServiceException() {
    var client = mock(ServiceHttpClient.class);
    when(client.send("http://identity.test", "/api/auth/me", "GET", null, null, "Bearer provider-token"))
        .thenThrow(new UpstreamUnavailableException());
    var resolver = new HttpUserIdentityResolver("http://identity.test", client, mapper);
    assertThatThrownBy(() -> resolver.resolve(jwt()))
        .isInstanceOf(AuthenticationServiceException.class)
        .hasCauseInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsUnauthorizedAndServerErrorsAppropriately() {
    var client = mock(ServiceHttpClient.class);
    var unauthorized = mock(HttpResponse.class);
    when(unauthorized.statusCode()).thenReturn(401);
    when(client.send("http://identity.test", "/api/auth/me", "GET", null, null, "Bearer provider-token"))
        .thenReturn(unauthorized);
    var resolver = new HttpUserIdentityResolver("http://identity.test", client, mapper);
    assertThatThrownBy(() -> resolver.resolve(jwt()))
        .isInstanceOf(InvalidBearerTokenException.class);

    var ok = mock(HttpResponse.class);
    when(ok.statusCode()).thenReturn(200);
    when(ok.body())
        .thenReturn(
            "{\"subject\":\"alice\",\"email\":\"a@test\",\"name\":\"Alice\",\"role\":\"CUSTOMER\"}"
                .getBytes(StandardCharsets.UTF_8));
    when(client.send("http://identity.test", "/api/auth/me", "GET", null, null, "Bearer provider-token"))
        .thenReturn(ok);
    assertThat(resolver.resolve(jwt()).role()).isEqualTo(UserRole.CUSTOMER);
  }
}
