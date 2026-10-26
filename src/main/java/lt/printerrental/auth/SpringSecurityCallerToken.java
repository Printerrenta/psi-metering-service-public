package lt.printerrental.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SpringSecurityCallerToken implements CallerToken {
  public String bearer() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt))
      throw new AccessDeniedException("Authentication required");
    return "Bearer " + jwt.getTokenValue();
  }
}
