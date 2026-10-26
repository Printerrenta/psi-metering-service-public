package lt.printerrental.auth;

public record AppUser(String subject, String email, String name, UserRole role) {}
