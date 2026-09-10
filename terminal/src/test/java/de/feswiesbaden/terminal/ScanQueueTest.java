package de.feswiesbaden.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.feswiesbaden.terminal.model.Scan;
import de.feswiesbaden.terminal.queue.ScanQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanQueueTest {

  @Test
  void behaeltScansBisSieEntferntWerden(@TempDir Path ordner) throws IOException {
    ScanQueue puffer = new ScanQueue(ordner.resolve("puffer.jsonl"));
    Scan scan = Scan.of("TEST-UID-001", 3);

    puffer.add(scan);

    List<Scan> offen = puffer.pending();
    assertEquals(1, offen.size());
    assertEquals("TEST-UID-001", offen.get(0).rfidUid());
    assertEquals(scan.scanId(), offen.get(0).scanId());
    assertEquals(scan.capturedAt(), offen.get(0).capturedAt());
  }

  @Test
  void entferntNurDenZugestelltenScan(@TempDir Path ordner) throws IOException {
    ScanQueue puffer = new ScanQueue(ordner.resolve("puffer.jsonl"));
    Scan erster = Scan.of("UID-A", 3);
    puffer.add(erster);
    puffer.add(Scan.of("UID-B", 3));

    puffer.remove(erster.scanId());

    List<Scan> offen = puffer.pending();
    assertEquals(1, offen.size());
    assertEquals("UID-B", offen.get(0).rfidUid());
  }

  @Test
  void ueberstehtEinenNeustart(@TempDir Path ordner) throws IOException {
    Path datei = ordner.resolve("puffer.jsonl");
    new ScanQueue(datei).add(Scan.of("UID-C", 3));

    // Neue Instanz steht für einen Neustart der Anwendung.
    ScanQueue nachNeustart = new ScanQueue(datei);

    assertEquals(1, nachNeustart.size());
    assertEquals("UID-C", nachNeustart.pending().get(0).rfidUid());
  }

  @Test
  void ueberspringtKaputteZeilenStattAufzugeben(@TempDir Path ordner) throws IOException {
    Path datei = ordner.resolve("puffer.jsonl");
    ScanQueue puffer = new ScanQueue(datei);
    puffer.add(Scan.of("UID-D", 3));

    Files.writeString(
        datei,
        "das ist kein json" + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    puffer.add(Scan.of("UID-E", 3));

    List<Scan> offen = puffer.pending();
    assertEquals(2, offen.size());
    assertTrue(offen.stream().anyMatch(s -> s.rfidUid().equals("UID-E")));
  }

  @Test
  void istLeerWennNochNichtsGescanntWurde(@TempDir Path ordner) throws IOException {
    assertEquals(0, new ScanQueue(ordner.resolve("gibtsnicht.jsonl")).size());
  }
}
