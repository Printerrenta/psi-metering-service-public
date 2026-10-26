package lt.printerrental.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class UserAccess {
  private final CurrentUser user;

  public UserAccess(CurrentUser user) {
    this.user = user;
  }

  public void owner(String owner) {
    if (!user.isStaff() && !user.subject().equals(owner))
      throw new AccessDeniedException("Access denied");
  }

  public void staff() {
    if (!user.isStaff()) throw new AccessDeniedException("Staff access required");
  }
}
