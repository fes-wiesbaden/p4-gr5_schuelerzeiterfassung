package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "attendance.retention.scheduling.enabled=false")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class Issue22IntegrationTest {
  private static final String PASSWORD = " test-only-passwort ";

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired ObjectMapper mapper;
  @Autowired SessionRegistry sessions;
  @Autowired ServletWebServerApplicationContext context;
  private String hash;

  @BeforeEach
  void createAccounts() {
    hash = encoder.encode(PASSWORD);
    for (String role : List.of("LEHRKRAFT", "ADMINISTRATOR")) {
      jdbc.update(
          "INSERT INTO staff (first_name, last_name, username, password_hash, role) VALUES ('Test', ?, ?, ?, ?)",
          role,
          "test." + role.toLowerCase(java.util.Locale.ROOT),
          hash,
          role);
    }
  }

  @AfterEach
  void cleanup() {
    for (var session : manager().findSessions()) {
      session.expire();
    }
    assertThat(sessions.getAllPrincipals()).isEmpty();
    jdbc.update("DELETE FROM staff");
  }

  @ParameterizedTest
  @CsvSource({"lehrkraft,lehrkraft", "administrator,admin"})
  void logsInWithStoredBcryptAndPersistsOnlyPublicUser(
      String username, String role, CapturedOutput output) throws Exception {
    var client = new Client();
    assertThat(client.get("/api/me").statusCode()).isEqualTo(401);
    assertCookie(client.last, "XSRF-TOKEN", false);
    assertThat(client.cookies).doesNotContainKey("SESSION");
    String before = client.csrf();
    var login = client.login("test." + username, PASSWORD);
    assertThat(login.statusCode()).isEqualTo(200);
    assertCookie(login, "SESSION", true);
    assertCookie(login, "XSRF-TOKEN", false);
    assertThat(client.csrf()).isNotEqualTo(before);
    assertThat(mapper.readTree(login.body()).properties()).hasSize(2);
    assertThat(mapper.readTree(login.body()).path("role").asText()).isEqualTo(role);
    assertThat(mapper.readTree(login.body()).path("name").asText())
        .isEqualTo("Test " + username.toUpperCase(java.util.Locale.ROOT));
    assertThat(client.get("/api/me").body()).isEqualTo(login.body());
    assertThat(hash).startsWith("$2a$12$").hasSize(60);
    assertThat(encoder.matches(PASSWORD, hash)).isTrue();
    assertThat(login.body()).doesNotContain(PASSWORD, hash, "password", "username");
    assertThat(output.getAll()).doesNotContain(PASSWORD, hash);
  }

  @Test
  void rotatesSessionIdAndInvalidatesOldIdOnReauthentication() throws Exception {
    var client = authenticated();
    String old = client.cookies.get("SESSION");
    assertThat(client.login("test.administrator", PASSWORD).statusCode()).isEqualTo(200);
    assertThat(client.cookies.get("SESSION")).isNotEqualTo(old);
    var attacker = new Client();
    attacker.cookies.put("SESSION", old);
    assertThat(attacker.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(200);
    assertThat(sessions.getAllSessions(sessions.getAllPrincipals().getFirst(), false)).hasSize(1);
  }

  @Test
  void credentialErrorsAreNeutralAndDoNotDisplaceExistingLogin() throws Exception {
    var existing = authenticated();
    var other = new Client();
    other.get("/api/me");
    var wrongPassword = other.login("test.administrator", "wrong-test-password");
    var unknownAccount = other.login("test.unknown", PASSWORD);
    assertThat(wrongPassword.statusCode()).isEqualTo(401);
    assertThat(unknownAccount.statusCode()).isEqualTo(401);
    assertThat(wrongPassword.body()).isEqualTo("{\"code\":\"LOGIN_FAILED\"}");
    assertThat(unknownAccount.body()).isEqualTo(wrongPassword.body());
    assertThat(other.cookies).doesNotContainKey("SESSION");
    assertThat(existing.get("/api/me").statusCode()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "{}{}",
        "null",
        "[]",
        "{}",
        "{\"username\":5,\"password\":\"x\"}",
        "{\"username\":\"test.administrator\",\"password\":\"\"}"
      })
  void rejectsInvalidJsonWithoutEchoingIt(String body) throws Exception {
    var client = new Client();
    client.get("/api/me");
    var response = client.post("/api/login", body, "application/json", client.csrf());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).isEmpty();
    assertThat(client.cookies).doesNotContainKey("SESSION");
  }

  @Test
  void validatesUtf8PasswordLimitAndMediaType() throws Exception {
    var client = new Client();
    client.get("/api/me");
    assertThat(client.login("test.administrator", "ä".repeat(37)).statusCode()).isEqualTo(400);
    assertThat(client.login(" ", PASSWORD).statusCode()).isEqualTo(400);
    assertThat(client.login("x".repeat(121), PASSWORD).statusCode()).isEqualTo(400);
    assertThat(client.post("/api/login", "username=test", "text/plain", client.csrf()).statusCode())
        .isEqualTo(415);
    assertThat(client.post("/api/login", "{}", "application/*", client.csrf()).statusCode())
        .isEqualTo(415);
    assertThat(client.get("/api/login").statusCode()).isEqualTo(401);
  }

  @Test
  void requiresCsrfForLoginLogoutAndOtherWritesAndRenewsItAfterLogin() throws Exception {
    var client = new Client();
    client.get("/api/me");
    String body =
        mapper.writeValueAsString(Map.of("username", "test.administrator", "password", PASSWORD));
    assertThat(client.post("/api/login", body, "application/json", null).statusCode())
        .isEqualTo(403);
    assertThat(client.post("/api/login", body, "application/json", "wrong-token").statusCode())
        .isEqualTo(403);
    String oldToken = client.csrf();
    assertThat(client.login("test.administrator", PASSWORD).statusCode()).isEqualTo(200);
    assertThat(client.post("/api/logout", "{}", "application/json", oldToken).statusCode())
        .isEqualTo(403);
    assertThat(client.post("/api/logout", "{}", "application/json", null).statusCode())
        .isEqualTo(403);
    assertThat(client.post("/api/students", "{}", "application/json", null).statusCode())
        .isEqualTo(403);
    assertThat(client.get("/api/logout").statusCode()).isEqualTo(403);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(200);
    assertThat(client.get("/api/students").statusCode()).isEqualTo(403);
  }

  @Test
  void secondLoginExpiresOldSessionButOtherUsersRemainLoggedIn() throws Exception {
    var first = authenticated();
    var teacher = new Client();
    teacher.get("/api/me");
    teacher.login("test.lehrkraft", PASSWORD);
    var second = new Client();
    second.get("/api/me");
    assertThat(second.login("TEST.ADMINISTRATOR", PASSWORD).statusCode()).isEqualTo(200);
    assertThat(first.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(first.cookies).doesNotContainKey("SESSION");
    assertThat(second.get("/api/me").statusCode()).isEqualTo(200);
    assertThat(teacher.get("/api/me").statusCode()).isEqualTo(200);
  }

  @Test
  void displacedSessionBootstrapsCsrfForImmediateLogin() throws Exception {
    var displaced = authenticated();
    String oldToken = displaced.csrf();
    authenticated();

    var response = displaced.get("/api/me");
    assertThat(response.statusCode()).isEqualTo(401);
    assertCookie(response, "XSRF-TOKEN", false);
    assertThat(displaced.csrf()).isNotBlank().isNotEqualTo(oldToken);
    assertThat(displaced.login("test.administrator", PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void simultaneousLoginsLeaveExactlyOneAuthorizedSession() throws Exception {
    var first = new Client();
    var second = new Client();
    first.get("/api/me");
    second.get("/api/me");
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a =
          workers.submit(
              () -> {
                barrier.await();
                return first.login("test.administrator", PASSWORD).statusCode();
              });
      var b =
          workers.submit(
              () -> {
                barrier.await();
                return second.login("test.administrator", PASSWORD).statusCode();
              });
      assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(200);
      assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(200);
    }
    assertThat(List.of(first.get("/api/me").statusCode(), second.get("/api/me").statusCode()))
        .containsExactlyInAnyOrder(200, 401);
    assertThat(sessions.getAllSessions(sessions.getAllPrincipals().getFirst(), false)).hasSize(1);
  }

  @Test
  void logoutDeletesCookiesSessionAndRegistryEntry() throws Exception {
    var client = authenticated();
    String oldId = client.cookies.get("SESSION");
    var logout = client.post("/api/logout", "{}", "application/json", client.csrf());
    assertThat(logout.statusCode()).isEqualTo(204);
    assertCookie(logout, "SESSION", true);
    assertThat(logout.headers().allValues("Set-Cookie"))
        .anySatisfy(header -> assertThat(header).startsWith("SESSION=").contains("Max-Age=0"))
        .anySatisfy(header -> assertThat(header).startsWith("XSRF-TOKEN=").contains("Max-Age=0"));
    assertThat(client.cookies).doesNotContainKeys("SESSION", "XSRF-TOKEN");
    assertThat(manager().findSession(oldId)).isNull();
    assertThat(sessions.getAllPrincipals()).isEmpty();
    client.cookies.put("SESSION", oldId);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(401);
  }

  @Test
  void idleTimeoutIsThirtyMinutesAndRequestsExtendIt() throws Exception {
    var client = authenticated();
    var session = manager().findSession(client.cookies.get("SESSION"));
    assertThat(session.getMaxInactiveInterval()).isEqualTo(1800);
    session.setMaxInactiveInterval(2);
    TimeUnit.MILLISECONDS.sleep(1100);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(200);
    TimeUnit.MILLISECONDS.sleep(1100);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(200);
    TimeUnit.MILLISECONDS.sleep(2200);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(sessions.getAllPrincipals()).isEmpty();
  }

  @Test
  void healthIsPublicAndOtherRoutesStayClosed() throws Exception {
    var client = new Client();
    assertThat(client.get("/actuator/health").statusCode()).isEqualTo(200);
    assertThat(client.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(client.get("/api/students").statusCode()).isEqualTo(401);
  }

  private Manager manager() {
    var server = (TomcatWebServer) context.getWebServer();
    return ((Context) server.getTomcat().getHost().findChildren()[0]).getManager();
  }

  private Client authenticated() throws Exception {
    var client = new Client();
    client.get("/api/me");
    assertThat(client.login("test.administrator", PASSWORD).statusCode()).isEqualTo(200);
    return client;
  }

  private void assertCookie(HttpResponse<String> response, String name, boolean httpOnly) {
    String header =
        response.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(name + "="))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(header).contains("Path=/", "Secure", "SameSite=Strict");
    if (httpOnly) {
      assertThat(header).contains("HttpOnly");
    } else {
      assertThat(header).doesNotContain("HttpOnly");
    }
  }

  private class Client {
    private final Map<String, String> cookies = new HashMap<>();
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private HttpResponse<String> last;

    String csrf() {
      return cookies.get("XSRF-TOKEN");
    }

    HttpResponse<String> get(String path) throws Exception {
      return send(path, null, null, null);
    }

    HttpResponse<String> login(String username, String password) throws Exception {
      return post(
          "/api/login",
          mapper.writeValueAsString(Map.of("username", username, "password", password)),
          "application/json",
          csrf());
    }

    HttpResponse<String> post(String path, String body, String type, String token)
        throws Exception {
      return send(path, body, type, token);
    }

    private HttpResponse<String> send(String path, String body, String type, String token)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(15));
      // Test-only direct HTTP transport; production clients send these cookies exclusively over
      // HTTPS.
      if (!cookies.isEmpty()) {
        request.header(
            "Cookie",
            String.join(
                "; ",
                cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList()));
      }
      if (body != null) {
        request.header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body));
      }
      if (token != null) {
        request.header("X-XSRF-TOKEN", token);
      }
      last = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      for (String header : last.headers().allValues("Set-Cookie")) {
        for (var cookie : HttpCookie.parse(header)) {
          if (cookie.getMaxAge() == 0) {
            cookies.remove(cookie.getName());
          } else {
            cookies.put(cookie.getName(), cookie.getValue());
          }
        }
      }
      assertThat(last.headers().firstValue("Location")).isEmpty();
      return last;
    }
  }
}
