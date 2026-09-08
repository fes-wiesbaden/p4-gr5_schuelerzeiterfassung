package de.feswiesbaden.terminal.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScanSenderTest {

  @Test
  void zweihundertIstEineErfolgreicheBuchung() {
    assertEquals(SendResult.Status.ACCEPTED, ScanSender.interpret(200, null).status());
    assertEquals(SendResult.Status.ACCEPTED, ScanSender.interpret(201, null).status());
  }

  @Test
  void vierhundertIstEndgueltigAbgelehnt() {
    SendResult ergebnis = ScanSender.interpret(400, "SCAN_REJECTED");

    assertEquals(SendResult.Status.REJECTED, ergebnis.status());
    assertEquals("SCAN_REJECTED", ergebnis.code());
    assertEquals(true, ergebnis.isFinal());
  }

  // Der Scan war schon da, also ist er auch gebucht. Für den Schüler ist das
  // ein Erfolg, kein Fehler (Issue #26).
  @Test
  void schonEmpfangenerScanGiltAlsErfolg() {
    assertEquals(
        SendResult.Status.ACCEPTED, ScanSender.interpret(409, "SCAN_ALREADY_RECEIVED").status());
  }

  // Bei 500 ist der Server kaputt, nicht der Scan. Also nochmal versuchen,
  // statt ihn wegzuwerfen.
  @Test
  void fuenfhundertWirdSpaeterNochmalVersucht() {
    assertEquals(SendResult.Status.RETRY, ScanSender.interpret(500, null).status());
    assertEquals(false, ScanSender.interpret(503, null).isFinal());
  }

  @Test
  void ohneCodeGiltDerSammelcode() {
    ScanSender sender = new ScanSender("https://127.0.0.1:8444/api/scans", null);

    assertEquals("SCAN_REJECTED", sender.readCode(""));
    assertEquals("SCAN_REJECTED", sender.readCode("kein json"));
    assertEquals("SCAN_REJECTED", sender.readCode("{}"));
    assertEquals("SCAN_ID_CONFLICT", sender.readCode("{\"code\":\"SCAN_ID_CONFLICT\"}"));
  }
}
