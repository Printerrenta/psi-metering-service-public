package lt.printerrental.transport;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ServiceHttpClient {
  private static final Logger LOG = LoggerFactory.getLogger(ServiceHttpClient.class);
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
    String base = null;
    String normalizedPath = null;
    try {
      base = normalizeBaseUrl(serviceUrl);
      normalizedPath = normalizePath(path);
      var request =
          HttpRequest.newBuilder(URI.create(base + normalizedPath)).timeout(Duration.ofSeconds(30));
      if (contentType != null) request.header("Content-Type", contentType);
      if (bearer != null) request.header("Authorization", bearer);
      var cloud = credentials.identityToken(base);
      if (cloud != null) request.header("X-Serverless-Authorization", "Bearer " + cloud);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      LOG.warn(
          "Upstream HTTP {} request to {}{} interrupted",
          method,
          base,
          stripQuery(normalizedPath),
          error);
      throw new UpstreamUnavailableException();
    } catch (Exception error) {
      LOG.warn(
          "Upstream HTTP {} request to {}{} failed",
          method,
          base,
          stripQuery(normalizedPath),
          error);
      throw new UpstreamUnavailableException();
    }
  }

  private static String normalizeBaseUrl(String serviceUrl) {
    if (serviceUrl == null || serviceUrl.isBlank())
      throw new IllegalArgumentException("Service URL required");
    var trimmed = serviceUrl.strip();
    int end = trimmed.length();
    while (end > 0 && trimmed.charAt(end - 1) == '/') end--;
    return trimmed.substring(0, end);
  }

  private static String normalizePath(String path) {
    if (path == null || path.isEmpty()) return "";
    return path.startsWith("/") ? path : "/" + path;
  }

  private static String stripQuery(String path) {
    if (path == null) return "";
    int query = path.indexOf('?');
    return query >= 0 ? path.substring(0, query) : path;
  }
}
