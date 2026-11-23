package de.feswiesbaden.attendance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.controller.AttendanceAccountingController;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Prüft Terminalidentität und Scan-Zustellung über den produktiven Pfad (Issues #24 und #26) */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "attendance.terminal.proxy-token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "attendance.retention.scheduling.enabled=false",
      "attendance.accounting.scheduling.enabled=false"
    })
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql("/fixtures/attendance.sql")
@Import(TerminalScanIntegrationTest.ScanBodyClock.class)
class TerminalScanIntegrationTest {
  private static final int TERMINAL_NUMBER = 23102003;

  private static final String SCAN_TIME = "2026-09-14T06:00:00Z";

  private static final String RECEIPT_TIME = "2026-09-14T08:45:00Z";

  private static final String REJECTED = "{\"code\":\"SCAN_REJECTED\"}";

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @TempDir static Path certificates;

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @MockitoBean Clock clock;

  @Autowired ScanBodyClock scanBodyClock;

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
    assertThat(generator.start().waitFor())
        .withFailMessage(() -> readLog("generation.log"))
        .isZero();

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
    assertThat(wrongCertificate.start().waitFor())
        .withFailMessage(() -> readLog("wrong-client.log"))
        .isZero();

    org.testcontainers.Testcontainers.exposeHostPorts(port);
    String config =
        Files.readString(Path.of("../infra/nginx/development.conf"))
            .replace("host.docker.internal:8080", "host.testcontainers.internal:" + port);
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

  /** Setzt den Scanbestand und bereitet einen Schüler ohne bisherige Tagesbuchung vor */
  @BeforeEach
  void prepareScan() {
    when(clock.instant()).thenReturn(Instant.parse(RECEIPT_TIME));
    jdbc.update("INSERT INTO room (id, room_number) VALUES (3, 'TEST-R03')");
    jdbc.update("UPDATE terminal SET room_id = 3 WHERE terminal_id = 9001");
    jdbc.update("INSERT INTO terminal (terminal_id, room_id) VALUES (?, 1)", TERMINAL_NUMBER);
    jdbc.update("DELETE FROM raw_scan");
    jdbc.update("DELETE FROM attendance_audit WHERE student_id = 1");
    jdbc.update("DELETE FROM attendance WHERE student_id = 1");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 0 WHERE id = 1");
  }

  /** Entfernt die Testdaten in der Reihenfolge ihrer Abhängigkeiten */
  @AfterEach
  void cleanup() {
    for (String table :
        List.of(
            "attendance_audit",
            "attendance",
            "raw_scan",
            "class_block_assignment",
            "teacher_class",
            "student",
            "school_class",
            "block_assignment",
            "timetable_slot",
            "terminal",
            "room",
            "staff",
            "block_plan",
            "timetable",
            "deletion_date")) {
      jdbc.update("DELETE FROM " + table);
    }
  }

  /** Prüft, dass nginx Anfragen ohne Clientzertifikat ablehnt */
  @Test
  void rejectsMissingClientCertificate() throws Exception {
    var response =
        send(client(null), proxyRequest(scanBody(UUID.randomUUID(), TERMINAL_NUMBER, SCAN_TIME)));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("No required SSL certificate was sent");
    assertThat(rawCount()).isZero();
  }

  /** Prüft, dass nginx ein gesendetes Zertifikat einer fremden CA ablehnt */
  @Test
  void rejectsClientCertificateFromWrongCa() throws Exception {
    // OpenSSL sendet das angegebene Zertifikat auch dann, wenn nginx eine andere CA erwartet
    var uri = proxyRequest(scanBody(UUID.randomUUID(), TERMINAL_NUMBER, SCAN_TIME)).build().uri();
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
            ("POST "
                    + uri.getRawPath()
                    + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(UTF_8));
      }
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(new String(process.getInputStream().readAllBytes(), UTF_8))
          .startsWith("HTTP/1.1 400 ")
          .contains("The SSL certificate error");
      assertThat(rawCount()).isZero();
    } finally {
      process.destroyForcibly();
    }
  }

  /** Prüft die Bindung der gemeldeten Terminal-ID an das Zertifikat */
  @Test
  void rejectsMismatchedIdentityThroughNginx() throws Exception {
    var response = send(terminal, proxyRequest(scanBody(UUID.randomUUID(), 9002, SCAN_TIME)));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).isEqualTo(REJECTED);
    assertThat(rawCount()).isZero();
  }

  /** Prüft die persistierte Terminalzuordnung auch bei gültigem Clientzertifikat */
  @Test
  void rejectsUnknownTerminalDespiteValidCertificate() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = ?", TERMINAL_NUMBER);
    assertThat(send(terminal, scanRequest(UUID.randomUUID())).statusCode()).isEqualTo(403);
    assertThat(rawCount()).isZero();
  }

  /** Prüft, dass die aktuelle Raumzuordnung für die Buchung gilt */
  @Test
  void usesCurrentRoomFromDatabase() throws Exception {
    jdbc.update("DELETE FROM terminal WHERE terminal_id = 9002");
    jdbc.update("UPDATE terminal SET room_id = 2 WHERE terminal_id = ?", TERMINAL_NUMBER);
    var wrongRoom = send(terminal, scanRequest(UUID.randomUUID()));
    assertThat(wrongRoom.statusCode()).isEqualTo(400);
    assertThat(wrongRoom.body()).isEqualTo(REJECTED);
    assertNoBooking();

    jdbc.update("UPDATE terminal SET room_id = 1 WHERE terminal_id = ?", TERMINAL_NUMBER);
    assertThat(send(terminal, scanRequest(UUID.randomUUID())).statusCode()).isEqualTo(204);
    assertThat(rawCount()).isEqualTo(2);
  }

  /**
   * Prüft die neutrale Ablehnung statt eines technischen Fehlers, wenn eine Blockzuordnung zu einem
   * fremden Plan gehört. Das Terminal darf einen solchen Scan nicht endlos wiederholen
   */
  @Test
  void rejectsScanWhenABlockAssignmentBelongsToAnotherPlan() throws Exception {
    jdbc.update(
        "INSERT INTO block_plan (id, name, starts_on, ends_on)"
            + " VALUES (99, 'Fremdplan', '2030-01-01', '2030-01-05')");
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)"
            + " VALUES (99, 99, '2026-09-14', '2026-09-18')");
    jdbc.update(
        "INSERT INTO class_block_assignment (school_class_id, block_assignment_id)"
            + " VALUES (1, 99)");
    var response = send(terminal, scanRequest(UUID.randomUUID()));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).isEqualTo(REJECTED);
    assertNoBooking();
  }

  /** Prüft die von nginx gesetzte Identität am echten Scan-Endpunkt */
  @Test
  void nginxOverwritesClientSuppliedIdentityHeaders() throws Exception {
    var request =
        scanRequest(UUID.randomUUID())
            .header("X-Terminal-Client-Subject", "CN=terminal-9002")
            .header("X-Terminal-Client-Verify", "NONE")
            .header("X-Terminal-Proxy-Token", "forged");
    assertThat(send(terminal, request).statusCode()).isEqualTo(204);
    assertThat(rawCount()).isOne();
  }

  /** Prüft die Ablehnung direkter Anfragen mit vorgetäuschter Identität */
  @Test
  void rejectsDirectBackendRequestsWithForgedHeaders() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/scans"))
            .header("Content-Type", "application/json")
            .header("X-Terminal-Client-Subject", "CN=terminal-" + TERMINAL_NUMBER)
            .header("X-Terminal-Client-Verify", "SUCCESS")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    scanBody(UUID.randomUUID(), TERMINAL_NUMBER, SCAN_TIME)));
    var direct = HttpClient.newHttpClient();
    assertThat(send(direct, request).statusCode()).isEqualTo(403);
    request.header("X-Terminal-Proxy-Token", "forged");
    assertThat(send(direct, request).statusCode()).isEqualTo(403);
    assertThat(rawCount()).isZero();
  }

  /** Prüft, dass parallele Zustellungen derselben ID genau einmal buchen */
  @Test
  void processesConcurrentDeliveriesOfOneIdOnlyOnce() throws Exception {
    var scanId = UUID.randomUUID();
    var request = scanRequest(scanId).timeout(Duration.ofSeconds(10)).build();
    var first = terminal.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    var second = terminal.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    var responses = List.of(first.join(), second.join());
    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(204, 409);
    assertThat(responses.stream().filter(response -> response.statusCode() == 409))
        .extracting(HttpResponse::body)
        .containsExactly("{\"code\":\"SCAN_ALREADY_RECEIVED\"}");
    var repeat =
        send(terminal, proxyRequest(scanBody(scanId, "UNKNOWN-UID", TERMINAL_NUMBER, SCAN_TIME)));
    assertThat(repeat.statusCode()).isEqualTo(409);
    assertThat(repeat.body()).isEqualTo("{\"code\":\"SCAN_ALREADY_RECEIVED\"}");
    assertThat(rawCount()).isOne();
    assertThat(scanAuditCount()).isOne();
    assertThat(jdbc.queryForObject("SELECT scanned_at FROM raw_scan", LocalDateTime.class))
        .isEqualTo("2026-09-14T06:00:00");
    assertThat(jdbc.queryForObject("SELECT created_at FROM raw_scan", LocalDateTime.class))
        .isEqualTo("2026-09-14T08:45:00");
  }

  /** Prüft, dass ein bei Eingang zukünftiger Zeitpunkt auch nach Zeitfortschritt abgelehnt wird */
  @Test
  void rejectsCaptureAfterReceiptEvenIfTheClockMovesDuringProcessing() throws Exception {
    when(clock.instant()).thenReturn(Instant.parse(SCAN_TIME));
    var processingTime = Instant.parse("2026-09-14T06:00:02Z");
    scanBodyClock.nextBodyRead.set(processingTime);
    var response =
        send(
            terminal,
            proxyRequest(scanBody(UUID.randomUUID(), TERMINAL_NUMBER, "2026-09-14T06:00:01Z")));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).isEqualTo(REJECTED);
    assertThat(clock.instant()).isEqualTo(processingTime);
    assertThat(rawCount()).isOne();
    assertThat(jdbc.queryForObject("SELECT created_at FROM raw_scan", LocalDateTime.class))
        .isEqualTo("2026-09-14T06:00:00");
    assertNoBooking();
  }

  /** Lässt die Testuhr beim Einlesen des nächsten Scanauftrags vorrücken */
  @TestComponent
  @ControllerAdvice(assignableTypes = AttendanceAccountingController.class)
  static class ScanBodyClock extends RequestBodyAdviceAdapter {
    private final Clock clock;

    private final AtomicReference<Instant> nextBodyRead = new AtomicReference<>();

    ScanBodyClock(Clock clock) {
      this.clock = clock;
    }

    @Override
    public boolean supports(
        MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
      return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
        HttpInputMessage input,
        MethodParameter parameter,
        Type type,
        Class<? extends HttpMessageConverter<?>> converter) {
      var nextTime = nextBodyRead.getAndSet(null);
      if (nextTime != null) {
        when(clock.instant()).thenReturn(nextTime);
      }
      return input;
    }
  }

  /** Prüft eine endgültige, gleichlautende Antwort für fachliche Ablehnungen */
  @Test
  void rejectsNewIdAtPresentAndKeepsOnlyTheRawScan() throws Exception {
    var attendance = jdbc.queryForList("SELECT * FROM attendance WHERE student_id = 5");
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit WHERE student_id = 5");
    var response =
        send(
            terminal,
            proxyRequest(scanBody(UUID.randomUUID(), "TEST-UID-005", TERMINAL_NUMBER, SCAN_TIME)));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).isEqualTo(REJECTED);
    assertThat(jdbc.queryForList("SELECT * FROM attendance WHERE student_id = 5"))
        .isEqualTo(attendance);
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit WHERE student_id = 5"))
        .isEqualTo(audits);
    assertThat(rawCount()).isOne();

    var unknown =
        send(
            terminal,
            proxyRequest(scanBody(UUID.randomUUID(), "UNKNOWN-UID", TERMINAL_NUMBER, SCAN_TIME)));
    assertThat(unknown.statusCode()).isEqualTo(response.statusCode());
    assertThat(unknown.body()).isEqualTo(response.body());
    assertThat(rawCount()).isEqualTo(2);
  }

  /** Prüft, dass eine technische Störung die ganze Buchung zurückrollt und einen Retry erlaubt */
  @Test
  void rollsBackOnAuditFailureAndAcceptsRetryOfTheSameId() throws Exception {
    var scanId = UUID.randomUUID();
    jdbc.execute(
        "ALTER TABLE attendance_audit ADD CONSTRAINT test_reject_scan CHECK (student_id <> 1)");
    try {
      var response = send(terminal, scanRequest(scanId));
      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body()).isEmpty();
      assertThat(rawCount()).isZero();
      assertNoBooking();
    } finally {
      jdbc.execute("ALTER TABLE attendance_audit DROP CHECK test_reject_scan");
    }
    assertThat(send(terminal, scanRequest(scanId)).statusCode()).isEqualTo(204);
    assertThat(rawCount()).isOne();
    assertThat(scanAuditCount()).isOne();
  }

  /** Prüft neutrale Ablehnungen für unlesbare und unvollständige Aufträge */
  @ParameterizedTest
  @ValueSource(strings = {"{", "{}"})
  void rejectsMalformedRequestsWithoutWritingRawScans(String body) throws Exception {
    var response = send(terminal, proxyRequest(body));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).isEqualTo(REJECTED);
    assertThat(rawCount()).isZero();
  }

  private void assertNoBooking() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE student_id = 1", Integer.class))
        .isZero();
    assertThat(scanAuditCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 1", Integer.class))
        .isZero();
  }

  private int rawCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class);
  }

  private int scanAuditCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 1", Integer.class);
  }

  private HttpRequest.Builder scanRequest(UUID scanId) {
    return proxyRequest(scanBody(scanId, TERMINAL_NUMBER, SCAN_TIME));
  }

  private HttpRequest.Builder proxyRequest(String body) {
    return HttpRequest.newBuilder(
            URI.create("https://localhost:" + nginx.getMappedPort(8444) + "/api/scans"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  private String scanBody(UUID scanId, int terminalNumber, String capturedAt) {
    return scanBody(scanId, "TEST-UID-001", terminalNumber, capturedAt);
  }

  private String scanBody(UUID scanId, String rfidUid, int terminalNumber, String capturedAt) {
    return """
        {"scanId":"%s","rfidUid":"%s","terminalNumber":%d,"capturedAt":"%s"}
        """
        .formatted(scanId, rfidUid, terminalNumber, capturedAt);
  }

  private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
      throws Exception {
    return client.send(
        request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private String readLog(String file) {
    try {
      return Files.readString(certificates.resolve(file));
    } catch (java.io.IOException failure) {
      return failure.getMessage();
    }
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
}
