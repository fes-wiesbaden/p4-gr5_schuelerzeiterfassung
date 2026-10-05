package de.feswiesbaden.attendance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.feswiesbaden.attendance.login.JsonLoginFilter;
import de.feswiesbaden.attendance.login.SpaCsrfTokenRequestHandler;
import de.feswiesbaden.attendance.login.StaffLoginService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
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
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SessionRegistry sessionRegistry() {
    return new SessionRegistryImpl();
  }

  @Bean
  HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

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
          // ponytail: global registration lock; per-user locks if login throughput requires it.
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
          mapper.writeValue(
              response.getOutputStream(), staff.currentUser(authentication.getName()));
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
                    .requestMatchers(HttpMethod.GET, "/api/me")
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
        .addFilterAt(login, UsernamePasswordAuthenticationFilter.class)
        .addFilterAt(expired, ConcurrentSessionFilter.class)
        .build();
  }
}
