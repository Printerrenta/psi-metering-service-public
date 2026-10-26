package lt.printerrental.auth;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.security.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
  private final UserIdentityResolver users;

  public UserJwtAuthenticationConverter(UserIdentityResolver users) {
    this.users = users;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    var user = users.resolve(jwt);
    return new JwtAuthenticationToken(
        jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())), user.subject());
  }
}
