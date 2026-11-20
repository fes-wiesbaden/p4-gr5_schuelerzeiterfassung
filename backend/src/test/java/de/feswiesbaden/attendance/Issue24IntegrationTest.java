package de.feswiesbaden.attendance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.feswiesbaden.attendance.config.TerminalProxyAuthentication;
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
import java.util.concurrent.TimeUnit;
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

/** Prüft die Terminalidentität über nginx, Spring und MySQL */
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

  /** Erzeugt Testzertifikate und startet den nginx-Proxy */
  @BeforeAll
  void startProxy() throws Exception {
    Files.writeString(
        certificates.resolve("terminal-proxy.properties"),
        "attendance.terminal.proxy-token=" + "a".repeat(64) + "\n");
    var generator =
        new ProcessBuilder(
            "sh",
            "-ec",
            "HOST_UID=$(id -u) HOST_GID=$(id -g) exec sh ../infra/tls/generate-certificates.sh");
    generator.environment().put("CERT_DIR", certificates.toString());
    generator.environment().put("TLS_HOST", "localhost");
    generator.redirectErrorStream(true);
    generator.redirectOutput(certificates.resolve("generation.log").toFile());
    assertThat(generator.start().waitFor()).isZero();

    // Die Server-CA stellt ein Clientzertifikat aus, dem nginx nicht vertraut
    var wrongCertificate =
        new ProcessBuilder(
            "sh",
            "-ec",
            """
            openssl x509 -x509toreq -in terminal-23102003-client.crt \
              -signkey terminal-23102003-client.key -copy_extensions copy -out wrong-client.csr
            openssl x509 -req -in wrong-client.csr -CA ca.crt -CAkey ca.key \
              -copy_extensions copy -out wrong-client.crt
            openssl verify -purpose sslclient -CAfile ca.crt wrong-client.crt
            """);
    wrongCertificate.directory(certificates.toFile());
    wrongCertificate.redirectErrorStream(true);
    wrongCertificate.redirectOutput(certificates.resolve("wrong-client.log").toFile());
    assertThat(wrongCertificate.start().waitFor()).isZero();

    org.testcontainers.Testcontainers.exposeHostPorts(port);
    String config =
        Files.readString(Path.of("../infra/nginx/development.conf"))
            .replace(
                "http://host.docker.internal:8080/api/scans",
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

  /** Stoppt den Testproxy nach dem letzten Test */
  @AfterAll
  void stopProxy() {
    if (nginx != null) {
      nginx.stop();
    }
  }

  /** Legt vor jedem Test zwei Räume mit je einem Terminal an */
  @BeforeEach
  void createRooms() {
    jdbc.update("INSERT INTO room (id, room_number) VALUES (1, 'TEST-R01'), (2, 'TEST-R02')");
    jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (23102003, 1), (9002, 2)");
  }

  /** Prüft die leeren Scan- und Anwesenheitstabellen und entfernt die Testzuordnungen */
  @AfterEach
  void cleanup() {
    for (String table : new String[] {"attendance", "attendance_audit", "raw_scan"}) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
    }
    jdbc.update("DELETE FROM terminal");
    jdbc.update("DELETE FROM room");
  }

  /** Prüft die Raumauflösung bei passender Terminalnummer und gültigem Zertifikat */
  @Test
  void resolvesRoomThroughNginxWithMatchingCertificate() throws Exception {
    var response = send(terminal, proxyRequest(23102003));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("1");
  }

  /** Prüft, dass nginx Anfragen ohne Clientzertifikat ablehnt */
  @Test
  void rejectsMissingClientCertificate() throws Exception {
    var response = send(client(null), proxyRequest(23102003));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("No required SSL certificate was sent");
  }

  /** Prüft, dass nginx ein gesendetes Zertifikat einer fremden CA ablehnt */
  @Test
  void rejectsClientCertificateFromWrongCa() throws Exception {
    // OpenSSL sendet das angegebene Zertifikat auch dann, wenn nginx eine andere CA erwartet
    var uri = proxyRequest(23102003).build().uri();
    var process =
        new ProcessBuilder(
                "openssl",
                "s_client",
                "-connect",
                "localhost:" + uri.getPort(),
                "-verify_hostname",
                "localhost",
                "-verify_return_error",
                "-CAfile",
                "ca.crt",
                "-cert",
                "wrong-client.crt",
                "-key",
                "terminal-23102003-client.key",
                "-quiet")
            .directory(certificates.toFile())
            .redirectError(certificates.resolve("wrong-client-handshake.log").toFile())
            .start();
    try {
      try (var request = process.getOutputStream()) {
        request.write(
            ("GET "
                    + uri.getRawPath()
                    + "?"
                    + uri.getRawQuery()
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(UTF_8));
      }
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(new String(process.getInputStream().readAllBytes(), UTF_8))
          .startsWith("HTTP/1.1 400 ")
          .contains("The SSL certificate error");
    } finally {
      process.destroyForcibly();
    }
  }

  /** Prüft, dass Spring eine vom Zertifikat abweichende Terminalnummer ablehnt */
  @Test
  void rejectsTerminalNumberThatDoesNotMatchCertificate() throws Exception {
    assertThat(send(terminal, proxyRequest(9002)).statusCode()).isEqualTo(403);
  }

  /** Prüft, dass Spring ein unbekanntes Terminal trotz gültigem Zertifikat ablehnt */
  @Test
  void rejectsValidCertificateForUnknownTerminal() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = 23102003");
    assertThat(send(terminal, proxyRequest(23102003)).statusCode()).isEqualTo(403);
  }

  /** Prüft, dass die geänderte Raumzuordnung sofort gilt */
  @Test
  void usesCurrentRoomFromDatabase() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = 9002");
    jdbc.update("UPDATE terminal SET room_id = 2 WHERE terminal_id = 23102003");
    assertThat(send(terminal, proxyRequest(23102003)).body()).isEqualTo("2");
  }

  /** Prüft, dass nginx vom Client gesendete Identitätsheader überschreibt */
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

  /** Prüft, dass Spring gefälschte Header bei direkten Anfragen ablehnt */
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

  /** Baut die Anfrage an nginx mit der gemeldeten Terminalnummer */
  private HttpRequest.Builder proxyRequest(int terminalNumber) {
    return HttpRequest.newBuilder(
        URI.create(
            "https://localhost:"
                + nginx.getMappedPort(8444)
                + "/api/scans?terminalNumber="
                + terminalNumber));
  }

  /** Sendet eine GET-Anfrage mit zehn Sekunden Zeitlimit */
  private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws Exception {
    return client.send(
        request.timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Erstellt einen Testclient mit der Server-CA und bei Bedarf einem Clientzertifikat */
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

  /** Stellt die Identitätsprüfung für den Test bereit. Der Scan-Endpunkt folgt in #26 */
  @TestConfiguration
  @RestController
  static class TestEndpoint {
    private final TerminalProxyAuthentication proxyAuthentication;

    private final TerminalIdentityService identity;

    /** Übernimmt die Proxy-Prüfung und den Service für die Raumauflösung */
    TestEndpoint(
        TerminalProxyAuthentication proxyAuthentication, TerminalIdentityService identity) {
      this.proxyAuthentication = proxyAuthentication;
      this.identity = identity;
    }

    /** Prüft den Proxy und liefert die Raum-ID für das gemeldete Terminal */
    @GetMapping("/__terminal-identity-test__")
    Long room(HttpServletRequest request, @RequestParam int terminalNumber) {
      return identity.resolveRoom(proxyAuthentication.verifiedSubject(request), terminalNumber);
    }

    /** Richtet die Sicherheitsregeln für den Testpfad ein und meldet Ablehnungen mit HTTP 403 */
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
