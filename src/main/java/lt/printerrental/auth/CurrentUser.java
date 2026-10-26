package lt.printerrental.auth;

/** Identity needed by application services, independent of the HTTP/security adapter. */
public interface CurrentUser {
  String subject();

  boolean hasRole(UserRole role);

  default boolean isStaff() {
    return hasRole(UserRole.ADMIN) || hasRole(UserRole.EMPLOYEE);
  }
}
