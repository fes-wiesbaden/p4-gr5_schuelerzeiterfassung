package de.feswiesbaden.terminal.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.feswiesbaden.terminal.model.Scan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

// Ein Scan wird sofort angehängt und erst gelöscht, wenn der Server geantwortet
// hat. Dadurch geht auch bei einem Absturz nichts verloren.
public final class ScanQueue {
  private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  private final Path file;

  public ScanQueue(Path file) {
    this.file = file;
  }

  public synchronized void add(Scan scan) throws IOException {
    String zeile = json.writeValueAsString(scan) + System.lineSeparator();
    Files.writeString(
        file, zeile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  public synchronized List<Scan> pending() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }

    List<Scan> offen = new ArrayList<>();
    for (String zeile : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (zeile.isBlank()) {
        continue;
      }
      // Eine kaputte Zeile überspringen, sonst wäre der ganze Puffer verloren.
      try {
        offen.add(json.readValue(zeile, Scan.class));
      } catch (IOException kaputt) {
        System.err.println("Puffereintrag übersprungen: " + kaputt.getMessage());
      }
    }
    return offen;
  }

  public synchronized void remove(String scanId) throws IOException {
    if (!Files.exists(file)) {
      return;
    }

    List<String> bleiben = new ArrayList<>();
    for (String zeile : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!zeile.isBlank() && !zeile.contains(scanId)) {
        bleiben.add(zeile);
      }
    }

    // Erst in eine neue Datei schreiben und dann umbenennen, damit der Puffer
    // nie halb geschrieben auf der Platte liegt.
    Path neu = file.resolveSibling(file.getFileName() + ".tmp");
    Files.write(neu, bleiben, StandardCharsets.UTF_8);
    Files.move(neu, file, StandardCopyOption.REPLACE_EXISTING);
  }

  public synchronized int size() throws IOException {
    return pending().size();
  }
}
