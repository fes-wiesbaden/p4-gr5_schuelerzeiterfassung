package de.feswiesbaden.attendance.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stellt die gemeinsame UTC-Uhr für zeitabhängige Backend-Regeln bereit */
@Configuration
public class TimeConfig {
  /** Liefert die Systemuhr mit UTC als Zeitzone */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
