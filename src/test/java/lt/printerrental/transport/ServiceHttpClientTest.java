package lt.printerrental.transport;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class ServiceHttpClientTest {
  @Test
  void keepsUserJwtSeparateFromGoogleServiceIdentity() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/test",
        exchange -> {
          var text =
              exchange.getRequestMethod()
                  + "|"
                  + exchange.getRequestHeaders().getFirst("Authorization")
                  + "|"
                  + exchange.getRequestHeaders().getFirst("X-Serverless-Authorization")
                  + "|"
                  + exchange.getRequestURI().getRawQuery()
                  + "|"
                  + new String(exchange.getRequestBody().readAllBytes());
          var bytes = text.getBytes();
          exchange.sendResponseHeaders(201, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var credentials =
          new ServiceCredentials() {
            public String identityToken(String audience) {
              return "google-token";
            }

            public String accessToken() {
              return null;
            }
          };
      var result =
          new ServiceHttpClient(credentials)
              .send(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "/api/test?size=1",
                  "POST",
                  "body".getBytes(),
                  "text/plain",
                  "Bearer neon-jwt");
      assertThat(result.statusCode()).isEqualTo(201);
      assertThat(new String(result.body()))
          .isEqualTo("POST|Bearer neon-jwt|Bearer google-token|size=1|body");
    } finally {
      server.stop(0);
    }
  }
}
