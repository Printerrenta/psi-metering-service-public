package lt.printerrental.transport;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class TransportExceptionHandler {
  @ExceptionHandler(UpstreamUnavailableException.class)
  ProblemDetail unavailable(UpstreamUnavailableException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, error.getMessage());
  }
}
