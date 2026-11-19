package de.feswiesbaden.terminal.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prüft die Einordnung von Serverantworten für die Scanzustellung */
class ScanSenderTest {

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

  /** Prüft, dass der Sender HTTP 200 und 201 als ACCEPTED einordnet */
  @Test
  void zweihundertIstEineErfolgreicheBuchung() {
    assertEquals(SendResult.Status.ACCEPTED, ScanSender.interpret(200, null).status());
    assertEquals(SendResult.Status.ACCEPTED, ScanSender.interpret(201, null).status());
  }

  /** Prüft, dass eine fachliche Ablehnung die Zustellung endgültig beendet */
  @Test
  void vierhundertIstEndgueltigAbgelehnt() {
    SendResult ergebnis = ScanSender.interpret(400, "SCAN_REJECTED");

    assertEquals(SendResult.Status.REJECTED, ergebnis.status());
    assertEquals("SCAN_REJECTED", ergebnis.code());
    assertEquals(true, ergebnis.isFinal());
  }

  /** Prüft, dass ein bereits empfangener Scan als Erfolg behandelt wird */
  @Test
  void schonEmpfangenerScanGiltAlsErfolg() {
    assertEquals(
        SendResult.Status.ACCEPTED, ScanSender.interpret(409, "SCAN_ALREADY_RECEIVED").status());
  }

  /** Prüft, dass Serverfehler einen erneuten Zustellversuch erlauben */
  @Test
  void fuenfhundertWirdSpaeterNochmalVersucht() {
    assertEquals(SendResult.Status.RETRY, ScanSender.interpret(500, null).status());
    assertEquals(false, ScanSender.interpret(503, null).isFinal());
  }

  /** Prüft die Übernahme des Duplikatcodes und die neutrale Behandlung anderer Antworten */
  @Test
  void ohneCodeGiltDerSammelcode() {
    assertEquals("SCAN_REJECTED", ScanSender.readCode(""));
    assertEquals("SCAN_REJECTED", ScanSender.readCode("kein json"));
    assertEquals("SCAN_REJECTED", ScanSender.readCode("{}"));
    assertEquals("SCAN_REJECTED", ScanSender.readCode("{\"code\":\"SCAN_ID_CONFLICT\"}"));
    assertEquals("SCAN_REJECTED", ScanSender.readCode("{\"code\":\"04A3B2C1\"}"));
    assertEquals(
        "SCAN_ALREADY_RECEIVED", ScanSender.readCode("{\"code\":\"SCAN_ALREADY_RECEIVED\"}"));
  }
}
