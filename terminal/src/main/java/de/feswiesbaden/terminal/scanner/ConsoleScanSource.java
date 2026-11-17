package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Liest Testscans ohne ESP32 über die Konsole ein */
public final class ConsoleScanSource implements ScanSource {
  private volatile boolean running;

  /** Startet den Konsolenleser in einem Hintergrundthread */
  @Override
  public void start(Consumer<Reading> onScan) {
    running = true;
    Thread leser = new Thread(() -> read(onScan), "console-reader");
    leser.setDaemon(true);
    leser.start();

    // TODO
    // Ohne eingetragenen seriellen Port steht diese Eingabe zum Testen ohne Hardware bereit
    System.out.println(
        "[TODO] Kein serieller Port eingetragen. Scans hier eintippen, zum Beispiel:");
    System.out.println("[TEST] {\"rfidUid\":\"04A3B2C1\",\"terminalNumber\":23102003}");
  }

  /** Liest Konsolenzeilen und reicht als Scan lesbare JSON-Zeilen an den Empfänger weiter */
  private void read(Consumer<Reading> onScan) {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

      String zeile;
      while (running && (zeile = in.readLine()) != null) {
        SerialLine.parse(zeile).ifPresent(onScan);
      }
    } catch (IOException ende) {
      // Konsole zu, nichts weiter zu tun
    }
  }

  /**
   * Fordert das Ende der Leseschleife an. Ein bereits laufendes readLine wartet weiter auf Eingabe
   * oder Eingabeende. Eine dabei gelesene Scanmeldung kann noch an den Empfänger gehen
   */
  @Override
  public void close() {
    running = false;
  }
}
