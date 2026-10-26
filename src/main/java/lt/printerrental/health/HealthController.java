package lt.printerrental.health;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final String name;

  public HealthController(@Value("${spring.application.name}") String name) {
    this.name = name;
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("service", name, "status", "UP");
  }
}
