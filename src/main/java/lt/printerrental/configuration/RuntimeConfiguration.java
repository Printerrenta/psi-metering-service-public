package lt.printerrental.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
