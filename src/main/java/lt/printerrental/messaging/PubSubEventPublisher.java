package lt.printerrental.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import lt.printerrental.events.*;
import lt.printerrental.transport.ServiceCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PubSubEventPublisher implements EventPublisher {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final URI endpoint;
  private final boolean emulator;
  private final ServiceCredentials credentials;
  private final ObjectMapper mapper;

  public PubSubEventPublisher(
      ServiceCredentials credentials,
      ObjectMapper mapper,
      @Value("${PUBSUB_PROJECT:printer-rental-local}") String project,
      @Value("${PUBSUB_TOPIC:metering-events-v1}") String topic,
      @Value("${PUBSUB_EMULATOR_HOST:}") String host) {
    if (!project.matches("[a-z0-9-]+") || !topic.matches("[a-zA-Z0-9._~-]+"))
      throw new IllegalArgumentException("Invalid Pub/Sub resource");
    if (System.getenv("K_SERVICE") != null && !host.isBlank())
      throw new IllegalStateException("Emulator is forbidden in Cloud Run");
    this.emulator = !host.isBlank();
    this.endpoint =
        URI.create(
            (emulator ? "http://" + host : "https://pubsub.googleapis.com")
                + "/v1/projects/"
                + project
                + "/topics/"
                + topic
                + ":publish");
    this.credentials = credentials;
    this.mapper = mapper;
  }

  public void publish(DomainEvent event) {
    try {
      var message =
          Map.of(
              "data",
              Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(event)),
              "attributes",
              Map.of(
                  "eventId",
                  event.eventId().toString(),
                  "type",
                  event.type(),
                  "schemaVersion",
                  "1"));
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json");
      if (!emulator) {
        var token = credentials.accessToken();
        if (token == null) throw new IllegalStateException("Cloud credentials required");
        request.header("Authorization", "Bearer " + token);
      }
      var response =
          client.send(
              request
                  .POST(
                      HttpRequest.BodyPublishers.ofByteArray(
                          mapper.writeValueAsBytes(Map.of("messages", List.of(message)))))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 200) throw new IllegalStateException("Pub/Sub publish failed");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Pub/Sub publish interrupted");
    } catch (Exception error) {
      throw new IllegalStateException("Pub/Sub publish failed");
    }
  }
}
