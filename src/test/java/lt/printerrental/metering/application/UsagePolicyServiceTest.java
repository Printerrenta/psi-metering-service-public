package lt.printerrental.metering.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import lt.printerrental.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class UsagePolicyServiceTest {
  private CurrentUser user(String subject, UserRole role) {
    return new CurrentUser() {
      @Override
      public String subject() {
        return subject;
      }

      @Override
      public boolean hasRole(UserRole candidate) {
        return candidate == role;
      }
    };
  }

  @Test
  void rejectsBoundsExceedingSixteenIntegerDigitsBeforePersistence() {
    var repo = mock(UsagePolicyRepository.class);
    var staff = user("employee", UserRole.EMPLOYEE);
    var service = new UsagePolicyService(repo, new UserAccess(staff), staff);

    assertThatThrownBy(
            () ->
                service.save(
                    "MODEL",
                    new UsagePolicyService.Change(
                        "maker/large",
                        BigDecimal.ZERO,
                        new BigDecimal("10000000000000000"),
                        0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximum supported value");

    assertThatThrownBy(
            () ->
                service.save(
                    "MODEL",
                    new UsagePolicyService.Change(
                        "maker/large", new BigDecimal("1E+16"), new BigDecimal("2E+16"), 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximum supported value");

    assertThatThrownBy(
            () ->
                service.save(
                    "MODEL",
                    new UsagePolicyService.Change(
                        "maker/large",
                        BigDecimal.ZERO,
                        new BigDecimal("10000000000000000.0000"),
                        0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximum supported value");

    verifyNoInteractions(repo);
  }

  @Test
  void allowsMaximumNumeric20Scale4Bound() {
    var repo = mock(UsagePolicyRepository.class);
    var staff = user("employee", UserRole.EMPLOYEE);
    var service = new UsagePolicyService(repo, new UserAccess(staff), staff);
    var max = new BigDecimal("9999999999999999.9999");
    var expected = new UsagePolicyRepository.Policy("MODEL", "maker/large", BigDecimal.ZERO, max, 1);
    when(repo.save("MODEL", "maker/large", BigDecimal.ZERO, max, 0, "employee"))
        .thenReturn(expected);

    var saved =
        service.save(
            "MODEL", new UsagePolicyService.Change("maker/large", BigDecimal.ZERO, max, 0));

    assertThat(saved).isEqualTo(expected);
  }

  @Test
  void rejectsInvalidDeviceUuidWithSafeMessageAndRequiresStaffRole() {
    var repo = mock(UsagePolicyRepository.class);
    var staff = user("employee", UserRole.EMPLOYEE);
    var staffService = new UsagePolicyService(repo, new UserAccess(staff), staff);
    assertThatThrownBy(
            () ->
                staffService.save(
                    "DEVICE",
                    new UsagePolicyService.Change(
                        "not-a-uuid", BigDecimal.ZERO, BigDecimal.TEN, 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Device policy key must be a valid UUID");

    var customer = user("alice", UserRole.CUSTOMER);
    var customerService = new UsagePolicyService(repo, new UserAccess(customer), customer);
    assertThatThrownBy(
            () ->
                customerService.save(
                    "MODEL",
                    new UsagePolicyService.Change("maker/large", BigDecimal.ZERO, BigDecimal.TEN, 0)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> customerService.get("MODEL", "maker/large"))
        .isInstanceOf(AccessDeniedException.class);
  }
}
