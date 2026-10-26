package lt.printerrental.auth;

public enum UserRole {
  CUSTOMER,
  EMPLOYEE,
  ADMIN;

  public boolean isStaff() {
    return this == EMPLOYEE || this == ADMIN;
  }
}
