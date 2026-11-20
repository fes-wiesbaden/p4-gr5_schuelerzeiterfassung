package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Prüft den echten Scan-Endpunkt über nginx, mTLS und Spring bis zur Datenbank. Die reine
 * Zertifikatsprüfung (gültige/abweichende Identität, nginx-CA, gefälschte Header) ist bereits in
 * Issue24IntegrationTest ausführlich geprüft; diese Klasse verifiziert zusätzlich, dass der echte
 * Scan-Controller diese Prüfung korrekt vorschaltet, bevor die Fachlogik erreicht wird, und dass
 * Antwortcode und -inhalt zum Terminal-Vertrag passen
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        "attendance.terminal.proxy-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue26IntegrationTest {
  private static final int TERMINAL_NUMBER = 23102003;

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @TempDir static Path certificates;

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private GenericContainer<?> nginx;

  private HttpClient terminal;

  /** Erzeugt Testzertifikate und startet den nginx-Proxy vor den echten Scan-Endpunkt */
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

    org.testcontainers.Testcontainers.exposeHostPorts(port);
    String config =
        Files.readString(Path.of("../infra/nginx/development.conf"))
            .replace(
                "http://host.docker.internal:8080/api/scans",
                "http://host.testcontainers.internal:" + port + "/api/scans");
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

  /** Legt vor jedem Test Raum und Terminal an */
  @BeforeEach
  void createTerminal() {
    jdbc.update("INSERT INTO room (id, room_number) VALUES (1, 'TEST-R01')");
    jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (?, 1)", TERMINAL_NUMBER);
  }

  /** Entfernt Testzuordnungen und Rohscans nach jedem Test */
  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM raw_scan");
    jdbc.update("DELETE FROM terminal");
    jdbc.update("DELETE FROM room");
  }

  /**
   * Eine gültige Identität erreicht die Fachlogik: der Rohscan wird gespeichert, auch wenn die
   * Kartenkennung keinem Schüler zugeordnet ist. SCAN_REJECTED bleibt neutral, ohne
   * Validierungsdetails
   */
  @Test
  void acceptsValidIdentityAndReachesBusinessLogic() throws Exception {
    var response = send(terminal, scanRequest(UUID.randomUUID(), "UNKNOWN-UID", TERMINAL_NUMBER));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("SCAN_REJECTED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isEqualTo(1);
  }

  /**
   * Eine vom Zertifikat abweichende Terminal-ID im Scan wird abgelehnt, bevor die Fachlogik
   * überhaupt den Rohscan speichert
   */
  @Test
  void rejectsMismatchedTerminalNumberBeforeBusinessLogic() throws Exception {
    var response = send(terminal, scanRequest(UUID.randomUUID(), "UNKNOWN-UID", 9999));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("SCAN_REJECTED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isZero();
  }

  /** Ein gültiges Zertifikat für ein unbekanntes Terminal wird abgelehnt */
  @Test
  void rejectsUnknownTerminalDespiteValidCertificate() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = ?", TERMINAL_NUMBER);
    var response = send(terminal, scanRequest(UUID.randomUUID(), "UNKNOWN-UID", TERMINAL_NUMBER));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isZero();
  }

  /**
   * Direkte Anfragen am Backend ohne den gültigen Proxy-Schlüssel werden abgelehnt, auch mit
   * vorgetäuschten nginx-Headern
   */
  @Test
  void rejectsDirectRequestsWithForgedProxyToken() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/scans"))
            .header("Content-Type", "application/json")
            .header("X-Terminal-Client-Subject", "CN=terminal-" + TERMINAL_NUMBER)
            .header("X-Terminal-Client-Verify", "SUCCESS")
            .header("X-Terminal-Proxy-Token", "forged")
            .timeout(Duration.ofSeconds(10))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    scanBody(UUID.randomUUID(), "UNKNOWN-UID", TERMINAL_NUMBER)));
    var response =
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isZero();
  }

  /**
   * Eine bereits bekannte Scan-ID liefert unabhängig von der Kartenkennung immer
   * SCAN_ALREADY_RECEIVED, bevor die Fachlogik erneut geprüft wird
   */
  @Test
  void duplicateScanIdAlwaysReturnsAlreadyReceived() throws Exception {
    var scanId = UUID.randomUUID();
    var first = send(terminal, scanRequest(scanId, "UNKNOWN-UID", TERMINAL_NUMBER));
    assertThat(first.statusCode()).isEqualTo(400);

    var second = send(terminal, scanRequest(scanId, "ANOTHER-UNKNOWN-UID", TERMINAL_NUMBER));
    assertThat(second.statusCode()).isEqualTo(409);
    assertThat(second.body()).contains("SCAN_ALREADY_RECEIVED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isEqualTo(1);
  }

  /** Baut die Scan-Anfrage an nginx mit den vom Terminal gesendeten Feldnamen */
  private HttpRequest.Builder scanRequest(UUID scanId, String rfidUid, int terminalNumber) {
    return HttpRequest.newBuilder(
            URI.create("https://localhost:" + nginx.getMappedPort(8444) + "/api/scans"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(scanBody(scanId, rfidUid, terminalNumber)));
  }

  /** Baut den JSON-Rumpf im selben Format wie der echte JavaFX-Terminalclient */
  private String scanBody(UUID scanId, String rfidUid, int terminalNumber) {
    return "{\"scanId\":\""
        + scanId
        + "\",\"rfidUid\":\""
        + rfidUid
        + "\",\"terminalNumber\":"
        + terminalNumber
        + ",\"capturedAt\":\""
        + Instant.now()
        + "\"}";
  }

  /** Sendet die vorbereitete Anfrage */
  private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Erstellt einen Testclient mit der Server-CA und dem Terminal-Clientzertifikat */
  private HttpClient client(String clientStore) throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(certificates.resolve("terminal-server-truststore.p12"))) {
      truststore.load(input, "changeit".toCharArray());
    }
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(truststore);
    KeyStore keystore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(certificates.resolve(clientStore))) {
      keystore.load(input, "changeit".toCharArray());
    }
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(keystore, "changeit".toCharArray());
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
  }
}
