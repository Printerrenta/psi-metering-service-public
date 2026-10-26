package lt.printerrental.transport;

public class UpstreamUnavailableException extends RuntimeException {
  public UpstreamUnavailableException() {
    super("A required service is unavailable; retry later");
  }
}
