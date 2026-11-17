package de.feswiesbaden.terminal.queue;

import de.feswiesbaden.terminal.model.Json;
import de.feswiesbaden.terminal.model.Scan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Speichert offene Scans bis zu einer endgültigen Serverantwort in einer Datei */
public final class ScanQueue {
  private final Path file;

  public ScanQueue(Path file) {
    this.file = file;
  }

  /** Hängt den Scan an die Datei an, damit er nach einem Neustart erneut zugestellt werden kann */
  public synchronized void add(Scan scan) throws IOException {
    Files.writeString(
        file,
        Json.mapper().writeValueAsString(scan) + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /**
   * Liest Scans in Dateireihenfolge. Nicht als Scan lesbare Zeilen werden mit einem neutralen
   * Fehlercode übersprungen. Die Datei selbst wird dabei nicht verändert
   */
  public synchronized List<Scan> pending() throws IOException {
    List<Scan> offen = new ArrayList<>();

    for (String zeile : zeilen()) {
      try {
        offen.add(Json.mapper().readValue(zeile, Scan.class));
      } catch (IOException kaputt) {
        // Andere lesbare Zeilen bleiben zustellbar. Die Jackson-Fehlermeldung wird nicht
        // ausgegeben, weil sie die Kartenkennung aus der fehlerhaften Zeile enthalten kann
        System.err.println("Puffereintrag übersprungen: INVALID_QUEUE_ENTRY");
      }
    }
    return offen;
  }

  /**
   * Entfernt alle Dateizeilen, die die angegebene Scan-ID enthalten, und erhält die übrigen Zeilen
   */
  public synchronized void remove(String scanId) throws IOException {
    List<String> bleiben = new ArrayList<>();
    for (String zeile : zeilen()) {
      if (!zeile.contains(scanId)) {
        bleiben.add(zeile);
      }
    }

    // Erst die Ersatzdatei vollständig schreiben. Scheitert das Schreiben, bleibt
    // die bisherige Warteschlangendatei erhalten. Erst danach wird sie ersetzt
    Path neu = file.resolveSibling(file.getFileName() + ".tmp");
    Files.write(neu, bleiben, StandardCharsets.UTF_8);
    Files.move(neu, file, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Zählt die lesbaren Scans in der Warteschlange */
  public synchronized int size() throws IOException {
    return pending().size();
  }

  /** Liest die nicht leeren Pufferzeilen oder liefert bei fehlender Datei eine leere Liste */
  private List<String> zeilen() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<String> gefuellt = new ArrayList<>();
    for (String zeile : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!zeile.isBlank()) {
        gefuellt.add(zeile);
      }
    }
    return gefuellt;
  }
}
