package lt.printerrental.api;

public class ApiProblem extends RuntimeException {
  private final int status;

  public ApiProblem(int status, String detail) {
    super(detail);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
