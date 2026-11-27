package de.feswiesbaden.attendance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.feswiesbaden.attendance.login.JsonLoginFilter;
import de.feswiesbaden.attendance.login.SpaCsrfTokenRequestHandler;
import de.feswiesbaden.attendance.login.StaffLoginService;
import de.feswiesbaden.attendance.login.StaffSessionFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/** Legt Anmeldung, Sitzungen und CSRF-Schutz für das Backend fest */
@Configuration
public class SecurityConfig {
  /** Erstellt den BCrypt-Encoder mit Kostenfaktor 12 */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /** Verwaltet die aktiven Sitzungen der angemeldeten Nutzer */
  @Bean
  SessionRegistry sessionRegistry() {
    return new SessionRegistryImpl();
  }

  /** Meldet beendete Sitzungen an Spring Security */
  @Bean
  HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  /** Schützt den Scanpfad mit nginx-Identität und ohne Staff-Sitzung */
  @Bean
  @Order(1)
  SecurityFilterChain terminalScanSecurity(
      HttpSecurity http, TerminalProxyAuthentication proxy, Clock clock, ObjectMapper mapper)
      throws Exception {
    return http.securityMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/scans"))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorization -> authorization.anyRequest().permitAll())
        .addFilterBefore(new TerminalScanFilter(proxy, clock, mapper), DisableEncodeUrlFilter.class)
        .build();
  }

  /** Richtet Zugriffsregeln, Anmeldung, Abmeldung und den Schutz der Sitzungen ein */
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      StaffLoginService staff,
      PasswordEncoder encoder,
      SessionRegistry sessions,
      ObjectMapper mapper)
      throws Exception {
    var provider = new DaoAuthenticationProvider(staff);
    provider.setPasswordEncoder(encoder);
    var context = new HttpSessionSecurityContextRepository();
    // Der Browser muss das CSRF-Token für den Anfrageheader lesen können.
    // Das Sitzungscookie wird davon getrennt als HttpOnly konfiguriert
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
    var csrfHandler = new SpaCsrfTokenRequestHandler();
    var csrfAuthentication = new CsrfAuthenticationStrategy(csrf);
    csrfAuthentication.setRequestHandler(csrfHandler);
    var concurrent = new ConcurrentSessionControlAuthenticationStrategy(sessions);
    concurrent.setMaximumSessions(1);
    var strategy =
        new CompositeSessionAuthenticationStrategy(
            List.of(
                concurrent,
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessions),
                csrfAuthentication)) {
          /**
           * Führt Sitzungsprüfung, ID-Wechsel und Registrierung bei parallelen Anmeldungen
           * nacheinander aus
           */
          @Override
          public synchronized void onAuthentication(
              Authentication authentication,
              HttpServletRequest request,
              HttpServletResponse response) {
            super.onAuthentication(authentication, request, response);
          }
        };

    var login = new JsonLoginFilter(mapper);
    login.setAuthenticationManager(new ProviderManager(provider));
    login.setSecurityContextRepository(context);
    login.setSessionAuthenticationStrategy(strategy);
    login.setAuthenticationSuccessHandler(
        (request, response, authentication) -> {
          response.setContentType("application/json");
          mapper.writeValue(response.getOutputStream(), staff.currentUser(authentication));
        });
    login.setAuthenticationFailureHandler(
        (request, response, exception) -> {
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          response.setContentType("application/json");
          mapper.writeValue(response.getOutputStream(), Map.of("code", "LOGIN_FAILED"));
        });

    var cookie = new Cookie("SESSION", "");
    cookie.setPath("/");
    cookie.setMaxAge(0);
    cookie.setHttpOnly(true);
    cookie.setSecure(true);
    cookie.setAttribute("SameSite", "Strict");
    var clearCookie = new CookieClearingLogoutHandler(cookie);
    var expired =
        new ConcurrentSessionFilter(
            sessions,
            event -> {
              csrf.loadDeferredToken(event.getRequest(), event.getResponse()).get();
              event.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            });
    expired.setLogoutHandlers(
        List.of(new SecurityContextLogoutHandler(), new CsrfLogoutHandler(csrf), clearCookie));

    return http.authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/login")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/assistant",
                        "/api/me",
                        "/api/class-options",
                        "/api/staff-id",
                        "/api/rooms",
                        "/api/teachers",
                        "/api/classes",
                        "/api/classes/{classId}/students",
                        "/api/classes/{classId}/attendance",
                        "/api/students",
                        "/api/attendance/current-class",
                        "/api/students/rfid-uids",
                        "/api/students/{studentId}/attendance/week",
                        "/api/students/{studentId}/attendance/calendar",
                        "/api/students/{studentId}")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/assistant/messages",
                        "/api/assistant/timetable-drafts",
                        "/api/assistant/excusals/preview",
                        "/api/assistant/excusals/{id}/confirm",
                        "/api/teachers",
                        "/api/rooms",
                        "/api/classes",
                        "/api/classes/{classId}/students",
                        "/api/classes/{classId}/account-resets",
                        "/api/students/{studentId}/attendance/{date}/status-corrections",
                        "/api/students/{studentId}/attendance/{date}/open-absence-excuses",
                        "/api/students/{studentId}/attendance/{date}/manual-absence-minutes",
                        "/api/students/{studentId}/attendance/{date}/status-periods",
                        "/api/students/{studentId}/attendance/{date}/school-day-excuses")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/planning",
                        "/api/planning/classes/{classId}/week",
                        "/api/planning/classes/{classId}/schedule")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/planning/timetables",
                        "/api/planning/timetables/import",
                        "/api/planning/timetables/{timetableId}/slots",
                        "/api/planning/block-plans",
                        "/api/planning/block-plans/{blockPlanId}/blocks",
                        "/api/planning/classes/{classId}/blocks",
                        "/api/planning/classes/{classId}/lesson-cancellations",
                        "/api/planning/classes/{classId}/block-plan-change")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/planning/timetables/{timetableId}/slots/{slotId}",
                        "/api/classes/{id}",
                        "/api/rooms/{id}",
                        "/api/teachers/{id}",
                        "/api/students/{studentId}",
                        "/api/students/{studentId}/rfid-uid")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/planning/timetables/{timetableId}/slots/{slotId}",
                        "/api/planning/classes/{classId}/lesson-cancellations",
                        "/api/classes/{id}",
                        "/api/rooms/{id}",
                        "/api/teachers/{id}",
                        "/api/students/{studentId}",
                        "/api/students/{studentId}/rfid-uid",
                        "/api/students/{studentId}/attendance/{date}/manual-absence-minutes")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .securityContext(security -> security.securityContextRepository(context))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .requestCache(cache -> cache.disable())
        .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrfHandler))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/logout")
                    .addLogoutHandler(clearCookie)
                    .logoutSuccessHandler(
                        (request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
        .addFilterBefore(new StaffSessionFilter(staff), AuthorizationFilter.class)
        .addFilterAt(login, UsernamePasswordAuthenticationFilter.class)
        .addFilterAt(expired, ConcurrentSessionFilter.class)
        .build();
  }
}
