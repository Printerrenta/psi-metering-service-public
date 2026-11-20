package lt.printerrental.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final int MAX_DETAIL_LENGTH = 200;
  private static final String DEFAULT_INVALID_DETAIL = "Invalid request or domain values";

  @ExceptionHandler(ApiProblem.class)
  ProblemDetail problem(ApiProblem e) {
    return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status()), e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalidDomain(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, safeDetail(e.getMessage()));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ProblemDetail invalid(Exception e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, DEFAULT_INVALID_DETAIL);
  }

  @ExceptionHandler({
    org.springframework.dao.DataIntegrityViolationException.class,
    org.springframework.dao.OptimisticLockingFailureException.class
  })
  ProblemDetail conflict(Exception e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "Concurrent or duplicate operation; reload and retry");
  }

  private static String safeDetail(String message) {
    if (message == null || message.isBlank()) return DEFAULT_INVALID_DETAIL;
    var trimmed = message.strip();
    if (trimmed.length() > MAX_DETAIL_LENGTH) return DEFAULT_INVALID_DETAIL;
    for (int i = 0; i < trimmed.length(); i++) {
      if (Character.isISOControl(trimmed.charAt(i))) return DEFAULT_INVALID_DETAIL;
    }
    return trimmed;
  }
}
