package lt.printerrental.transport;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ServiceHttpClient {
  private final ServiceCredentials credentials;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public ServiceHttpClient(ServiceCredentials credentials) {
    this.credentials = credentials;
  }

  public HttpResponse<byte[]> send(
      String serviceUrl,
      String path,
      String method,
      byte[] body,
      String contentType,
      String bearer) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(serviceUrl + path)).timeout(Duration.ofSeconds(120));
      if (contentType != null) request.header("Content-Type", contentType);
      if (bearer != null) request.header("Authorization", bearer);
      var cloud = credentials.identityToken(serviceUrl);
      if (cloud != null) request.header("X-Serverless-Authorization", "Bearer " + cloud);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException();
    } catch (Exception error) {
      throw new UpstreamUnavailableException();
    }
  }
}
