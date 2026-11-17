package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.feswiesbaden.attendance.service.TerminalIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        "attendance.terminal.proxy-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
@Import(Issue24IntegrationTest.TestEndpoint.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue24IntegrationTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @TempDir static Path certificates;

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private GenericContainer<?> nginx;

  private HttpClient terminal;

  @BeforeAll
  void startProxy() throws Exception {
    Files.writeString(
        certificates.resolve("terminal-proxy.properties"),
        "attendance.terminal.proxy-token=" + "a".repeat(64) + "\n");
    var generator = new ProcessBuilder("sh", "../infra/tls/generate-certificates.sh");
    generator.environment().put("CERT_DIR", certificates.toString());
    generator.environment().put("TLS_HOST", "localhost");
    generator
        .environment()
        .put("HOST_UID", Files.getAttribute(certificates, "unix:uid").toString());
    generator
        .environment()
        .put("HOST_GID", Files.getAttribute(certificates, "unix:gid").toString());
    generator.redirectErrorStream(true);
    generator.redirectOutput(certificates.resolve("generation.log").toFile());
    assertThat(generator.start().waitFor()).isZero();

    // Das Serverzertifikat stammt von einer anderen CA als die Terminalzertifikate.
    var wrongCertificate =
        new ProcessBuilder(
            "openssl",
            "pkcs12",
            "-export",
            "-out",
            "wrong-client.p12",
            "-inkey",
            "server.key",
            "-in",
            "server.crt",
            "-passout",
            "pass:changeit");
    wrongCertificate.directory(certificates.toFile());
    wrongCertificate.redirectErrorStream(true);
    wrongCertificate.redirectOutput(certificates.resolve("wrong-client.log").toFile());
    assertThat(wrongCertificate.start().waitFor()).isZero();

    org.testcontainers.Testcontainers.exposeHostPorts(port);
    String config =
        Files.readString(Path.of("../infra/nginx/development.conf"))
            .replace(
                "http://host.docker.internal:8080/actuator/health",
                "http://host.testcontainers.internal:" + port + "/__terminal-identity-test__");
    Path proxyDirectory = certificates.resolve("nginx");
    Files.createDirectories(proxyDirectory.resolve("conf.d"));
    Files.createDirectories(proxyDirectory.resolve("tls"));
    Files.writeString(proxyDirectory.resolve("conf.d/default.conf"), config);
    for (String file :
        new String[] {
          "server.crt", "server.key", "terminal-client-ca.crt", "terminal-proxy.conf"
        }) {
      Files.copy(certificates.resolve(file), proxyDirectory.resolve("tls/" + file));
    }
    nginx =
        new GenericContainer<>("nginx:1.28-alpine")
            .withExposedPorts(8444)
            .withCopyFileToContainer(MountableFile.forHostPath(proxyDirectory), "/etc/nginx")
            .waitingFor(Wait.forListeningPort());
    nginx.start();
    terminal = client("terminal-23102003-client.p12");
  }

  @AfterAll
  void stopProxy() {
    if (nginx != null) {
      nginx.stop();
    }
  }

  @BeforeEach
  void createRooms() {
    jdbc.update("INSERT INTO room (id, room_number) VALUES (1, 'TEST-R01'), (2, 'TEST-R02')");
    jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (23102003, 1), (9002, 2)");
  }

  @AfterEach
  void cleanup() {
    for (String table : new String[] {"attendance", "attendance_audit", "raw_scan"}) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
    }
    jdbc.update("DELETE FROM terminal");
    jdbc.update("DELETE FROM room");
  }

  @Test
  void resolvesRoomThroughNginxWithMatchingCertificate() throws Exception {
    var response = send(terminal, proxyRequest(23102003));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("1");
  }

  @Test
  void rejectsMissingClientCertificate() throws Exception {
    assertThat(send(client(null), proxyRequest(23102003)).statusCode()).isEqualTo(400);
  }

  @Test
  void rejectsClientCertificateFromWrongCa() throws Exception {
    assertThat(send(client("wrong-client.p12"), proxyRequest(23102003)).statusCode())
        .isEqualTo(400);
  }

  @Test
  void rejectsTerminalNumberThatDoesNotMatchCertificate() throws Exception {
    assertThat(send(terminal, proxyRequest(9002)).statusCode()).isEqualTo(403);
  }

  @Test
  void rejectsValidCertificateForUnknownTerminal() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = 23102003");
    assertThat(send(terminal, proxyRequest(23102003)).statusCode()).isEqualTo(403);
  }

  @Test
  void usesCurrentRoomFromDatabase() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = 9002");
    jdbc.update("UPDATE terminal SET room_id = 2 WHERE terminal_id = 23102003");
    assertThat(send(terminal, proxyRequest(23102003)).body()).isEqualTo("2");
  }

  @Test
  void nginxOverwritesClientSuppliedIdentityHeaders() throws Exception {
    var request =
        proxyRequest(23102003)
            .header("X-Terminal-Client-Subject", "CN=terminal-9002")
            .header("X-Terminal-Client-Verify", "NONE")
            .header("X-Terminal-Proxy-Token", "forged");
    assertThat(send(terminal, request).statusCode()).isEqualTo(200);
    var forgedIdentity = proxyRequest(9002).header("X-Terminal-Client-Subject", "CN=terminal-9002");
    assertThat(send(terminal, forgedIdentity).statusCode()).isEqualTo(403);
  }

  @Test
  void rejectsDirectBackendRequestsWithForgedHeaders() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/__terminal-identity-test__?terminalNumber=23102003"))
            .header("X-Terminal-Client-Subject", "CN=terminal-23102003")
            .header("X-Terminal-Client-Verify", "SUCCESS");
    assertThat(send(HttpClient.newHttpClient(), request).statusCode()).isEqualTo(403);
    request.header("X-Terminal-Proxy-Token", "forged");
    assertThat(send(HttpClient.newHttpClient(), request).statusCode()).isEqualTo(403);
  }

  @Test
  void databaseRequiresUniqueTerminalAndRoom() {
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (9003, 1)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (9003, NULL)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (9003, 999)"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private HttpRequest.Builder proxyRequest(int terminalNumber) {
    return HttpRequest.newBuilder(
        URI.create(
            "https://localhost:"
                + nginx.getMappedPort(8444)
                + "/api/__scan-endpoint-todo__?terminalNumber="
                + terminalNumber));
  }

  private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws Exception {
    return client.send(
        request.timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpClient client(String clientStore) throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(certificates.resolve("terminal-server-truststore.p12"))) {
      truststore.load(input, "changeit".toCharArray());
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(truststore);
    KeyManagerFactory keys = null;
    if (clientStore != null) {
      KeyStore keystore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(certificates.resolve(clientStore))) {
        keystore.load(input, "changeit".toCharArray());
      }
      keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(keystore, "changeit".toCharArray());
    }
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
  }

  // Nur im Test: Der echte Scan-Endpunkt folgt in #26.
  @TestConfiguration
  @RestController
  static class TestEndpoint {
    private final TerminalIdentityService identity;

    TestEndpoint(TerminalIdentityService identity) {
      this.identity = identity;
    }

    @GetMapping("/__terminal-identity-test__")
    Long room(HttpServletRequest request, @RequestParam int terminalNumber) {
      return identity.resolveRoom(request, terminalNumber);
    }

    @Bean
    @Order(0)
    SecurityFilterChain identityTestSecurity(HttpSecurity http) throws Exception {
      return http.securityMatcher("/__terminal-identity-test__")
          .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
          .exceptionHandling(
              errors ->
                  errors.authenticationEntryPoint(
                      (request, response, error) -> response.setStatus(403)))
          .build();
    }
  }
}
