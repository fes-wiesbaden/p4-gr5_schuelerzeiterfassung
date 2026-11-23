package de.feswiesbaden.terminal.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prüft die Einordnung von Serverantworten für die Scanzustellung */
class ScanSenderTest {
  private static final String REJECTED = "{\"code\":\"SCAN_REJECTED\"}";

  private static final String ALREADY_RECEIVED = "{\"code\":\"SCAN_ALREADY_RECEIVED\"}";

  /** Prüft die Ablehnung von Zieladressen ohne HTTPS-Schema oder Host */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/api/scans",
        "ftp://127.0.0.1/api/scans",
        "/api/scans",
        "https:/api/scans"
      })
  void lehntAdressenOhneHttpsUndHostAb(String adresse) {
    assertThrows(
        IllegalArgumentException.class, () -> new ScanSender(adresse, SSLContext.getDefault()));
  }

  /** Prüft, dass der Sender ohne TLS-Kontext nicht erstellt werden kann */
  @Test
  void verlangtEinenTlsKontext() {
    assertThrows(
        NullPointerException.class, () -> new ScanSender("https://127.0.0.1/api/scans", null));
  }

  @Test
  void nurDieErwarteteErfolgsantwortBestaetigtDieBuchung() {
    assertEquals(SendResult.accepted(), ScanSender.interpret(204, ""));
    assertEquals(SendResult.retry(), ScanSender.interpret(200, ""));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 403})
  void backendAblehnungIstEndgueltig(int status) {
    assertEquals(SendResult.rejected("SCAN_REJECTED"), ScanSender.interpret(status, REJECTED));
  }

  @Test
  void schonVerarbeiteterScanHatEinEigenesErgebnis() {
    assertEquals(SendResult.alreadyReceived(), ScanSender.interpret(409, ALREADY_RECEIVED));
    assertEquals(SendResult.retry(), ScanSender.interpret(409, REJECTED));
  }

  @Test
  void technischeServerfehlerBleibenRetrybar() {
    assertEquals(SendResult.retry(), ScanSender.interpret(500, REJECTED));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "<html>Bad Request</html>",
        "null",
        "{\"code\":\"04A3B2C1\"}",
        REJECTED + "{}"
      })
  void vierhundertOhneGueltigenBackendCodeBleibtRetrybar(String antwort) {
    assertEquals(SendResult.retry(), ScanSender.interpret(400, antwort));
  }
}
