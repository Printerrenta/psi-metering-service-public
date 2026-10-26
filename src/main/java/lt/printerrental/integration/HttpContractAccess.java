package lt.printerrental.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.auth.CallerToken;
import lt.printerrental.transport.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpContractAccess implements ContractAccess {
  private final ServiceHttpClient client;
  private final CallerToken caller;
  private final ObjectMapper json;
  private final String url;

  public HttpContractAccess(
      ServiceHttpClient client,
      CallerToken caller,
      ObjectMapper json,
      @Value("${RENTAL_SERVICE_URL}") String url) {
    this.client = client;
    this.caller = caller;
    this.json = json;
    this.url = url;
  }

  public ContractSnapshot get(UUID id) {
    var r =
        client.send(
            url, "/api/contracts/" + id + "/accounting", "GET", null, null, caller.bearer());
    if (r.statusCode() == 401 || r.statusCode() == 403)
      throw new org.springframework.security.access.AccessDeniedException("Contract access denied");
    if (r.statusCode() == 404 || r.statusCode() == 409)
      throw new ApiProblem(r.statusCode(), "Contract accounting is not initialized");
    if (r.statusCode() != 200) throw new UpstreamUnavailableException();
    try {
      return json.readValue(r.body(), ContractSnapshot.class);
    } catch (Exception e) {
      throw new UpstreamUnavailableException();
    }
  }
}
