package lt.printerrental.events;

public interface EventPublisher {
  void publish(DomainEvent event);
}
