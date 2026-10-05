package de.feswiesbaden.attendance.login;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

public class JsonLoginFilter extends UsernamePasswordAuthenticationFilter {
  private final ObjectMapper mapper;

  public JsonLoginFilter(ObjectMapper mapper) {
    this.mapper = mapper;
    setRequiresAuthenticationRequestMatcher(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/login"));
  }

  @Override
  public Authentication attemptAuthentication(
      HttpServletRequest request, HttpServletResponse response) {
    try {
      if (request.getContentType() == null
          || !MediaType.APPLICATION_JSON.includes(
              MediaType.parseMediaType(request.getContentType()))) {
        response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        return null;
      }
    } catch (IllegalArgumentException invalidType) {
      response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
      return null;
    }

    try {
      JsonNode body =
          mapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(request.getInputStream());
      if (body == null
          || !body.path("username").isTextual()
          || !body.path("password").isTextual()) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return null;
      }
      String username = body.path("username").textValue();
      String password = body.path("password").textValue();
      if (username.isBlank()
          || username.length() > 120
          || password.isEmpty()
          || password.getBytes(StandardCharsets.UTF_8).length > 72) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return null;
      }
      var token = UsernamePasswordAuthenticationToken.unauthenticated(username, password);
      setDetails(request, token);
      return getAuthenticationManager().authenticate(token);
    } catch (IOException invalidJson) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return null;
    }
  }
}
