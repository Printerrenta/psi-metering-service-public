package lt.printerrental.api;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void surfacesSafeDomainIllegalArgumentMessages() {
    var detail =
        handler.invalidDomain(
            new IllegalArgumentException("Policy bounds exceed maximum supported value"));
    assertThat(detail.getStatus()).isEqualTo(400);
    assertThat(detail.getDetail()).isEqualTo("Policy bounds exceed maximum supported value");
  }

  @Test
  void fallsBackForBlankOverlongOrControlCharacterMessages() {
    assertThat(handler.invalidDomain(new IllegalArgumentException((String) null)).getDetail())
        .isEqualTo("Invalid request or domain values");
    assertThat(handler.invalidDomain(new IllegalArgumentException("   ")).getDetail())
        .isEqualTo("Invalid request or domain values");
    assertThat(handler.invalidDomain(new IllegalArgumentException("a".repeat(201))).getDetail())
        .isEqualTo("Invalid request or domain values");
    assertThat(handler.invalidDomain(new IllegalArgumentException("bad\nmessage")).getDetail())
        .isEqualTo("Invalid request or domain values");
  }
}
