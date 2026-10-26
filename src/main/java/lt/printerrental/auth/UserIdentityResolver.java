package lt.printerrental.auth;

import org.springframework.security.oauth2.jwt.Jwt;

public interface UserIdentityResolver {
  AppUser resolve(Jwt jwt);
}
