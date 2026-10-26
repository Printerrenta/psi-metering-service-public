package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class UserJwtAuthenticationConverterTest {
  @Test
  void tokenRoleCannotElevateApplicationPermissions() {
    var users = mock(UserIdentityResolver.class);
    var jwt =
        Jwt.withTokenValue("signed-provider-token")
            .header("alg", "EdDSA")
            .subject("client")
            .claim("email", "client@example.test")
            .claim("name", "Client")
            .claim("role", "ADMIN")
            .build();
    when(users.resolve(jwt))
        .thenReturn(new AppUser("client", "client@example.test", "Client", UserRole.CUSTOMER));
    var auth = new UserJwtAuthenticationConverter(users).convert(jwt);
    assertThat(auth.getName()).isEqualTo("client");
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_CUSTOMER");
  }
}
