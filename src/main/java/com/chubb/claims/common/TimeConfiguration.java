package com.chubb.claims.common;

import java.time.Clock;
import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class TimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  ValidationConfigurationCustomizer validationClock(Clock clock) {
    return configuration -> configuration.clockProvider(() -> clock);
  }
}
