package de.feswiesbaden.attendance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  UserDetailsService userDetailsService() {
    return new InMemoryUserDetailsManager();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    // Ohne Anmeldung, weil am Terminal niemand eingeloggt ist.
                    .requestMatchers(HttpMethod.GET, "/api/terminals/*/events")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
