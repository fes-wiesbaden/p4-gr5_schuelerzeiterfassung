package de.feswiesbaden.attendance.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Startet Codex ohne Werkzeuge in einem eigenen temporären Verzeichnis und liefert dessen
 * strukturierte Antwort. Pro Person läuft höchstens eine Anfrage, insgesamt höchstens drei
 */
@Component
public class CodexRunner {
  /** Wartezeit auf einen freien Platz, bevor die Anfrage als ausgelastet abgelehnt wird */
  private static final long QUEUE_SECONDS = 5;

  private final boolean demo;

  private final String model;

  /** Höchstens drei Codex-Prozesse laufen gleichzeitig */
  private final Semaphore capacity = new Semaphore(3, true);

  /** Mitarbeiter-IDs mit einer gerade laufenden Anfrage */
  private final Set<Long> active = ConcurrentHashMap.newKeySet();

  public CodexRunner(
      @Value("${attendance.assistant.demo:false}") boolean demo,
      @Value("${attendance.assistant.model:}") String model) {
    this.demo = demo;
    this.model = model;
  }

  /** Meldet, ob das Backend für die Codex-Demo konfiguriert ist */
  public boolean enabled() {
    return demo;
  }

  /**
   * Führt eine Anfrage aus und liefert die JSON-Antwort als Text. Meldet ASSISTANT_BUSY, wenn die
   * Person bereits eine Anfrage laufen hat oder nach kurzer Wartezeit kein Platz frei ist, und
   * ASSISTANT_UNAVAILABLE bei abgeschalteter Demo, Zeitüberschreitung oder fehlerhafter Antwort
   */
  public String run(Request request) {
    if (!demo) {
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    }
    if (!active.add(request.staffId())) {
      throw new IllegalStateException("ASSISTANT_BUSY");
    }
    boolean acquired = false;
    try {
      acquired = capacity.tryAcquire(QUEUE_SECONDS, TimeUnit.SECONDS);
      if (!acquired) {
        throw new IllegalStateException("ASSISTANT_BUSY");
      }
      return execute(request);
    } catch (IOException failure) {
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    } finally {
      if (acquired) {
        capacity.release();
      }
      active.remove(request.staffId());
    }
  }

  /** Startet den Prozess; im selben Paket überschreibbar, damit Tests ohne Codex auskommen */
  String execute(Request request) throws IOException, InterruptedException {
    var directory = Files.createTempDirectory("attendance-assistant-");
    var schema = directory.resolve("reply.schema.json");
    var input = directory.resolve("prompt.txt");
    var output = directory.resolve("reply.json");
    // Der Dateiname entsteht serverseitig; vom hochgeladenen Namen wird nichts übernommen
    var image =
        request.image() == null ? null : directory.resolve("image." + request.image().extension());
    try {
      try (var source = new ClassPathResource(request.schemaResource()).getInputStream()) {
        Files.copy(source, schema);
      }
      Files.writeString(input, request.prompt());
      if (image != null) {
        Files.write(image, request.image().content());
      }
      var process =
          new ProcessBuilder(command(schema, output, image))
              .directory(directory.toFile())
              .redirectInput(input.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        if (!process.waitFor(request.timeoutSeconds(), TimeUnit.SECONDS)
            || process.exitValue() != 0
            || !Files.isRegularFile(output)
            || Files.size(output) > request.maxReplyBytes()) {
          throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
        }
        return Files.readString(output);
      } finally {
        if (process.isAlive()) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
        }
      }
    } finally {
      Files.deleteIfExists(output);
      Files.deleteIfExists(input);
      Files.deleteIfExists(schema);
      if (image != null) {
        Files.deleteIfExists(image);
      }
      Files.deleteIfExists(directory);
    }
  }

  private List<String> command(Path schema, Path output, Path image) {
    var command = new ArrayList<>(List.of("codex", "-a", "never", "exec"));
    if (image != null) {
      // Vor den übrigen Optionen, da --image mehrere Werte annimmt und sonst das abschließende
      // „-“ für die Eingabe verschlucken könnte
      command.add("--image");
      command.add(image.toString());
    }
    command.addAll(
        List.of(
            "--skip-git-repo-check",
            "--ephemeral",
            "--ignore-user-config",
            "--ignore-rules",
            "--sandbox",
            "read-only",
            "--disable",
            "shell_tool",
            "--disable",
            "incremental_tools",
            "--disable",
            "incremental_tools_legacy",
            "--disable",
            "plugins",
            "-c",
            "web_search=disabled",
            "-c",
            "model_reasoning_effort=\"low\"",
            "--output-schema",
            schema.toString(),
            "--output-last-message",
            output.toString()));
    if (!model.isBlank()) {
      command.add("--model");
      command.add(model);
    }
    command.add("-");
    return command;
  }

  /** Beschreibt eine Anfrage mit Schema aus dem Klassenpfad und optionalem Bild */
  public record Request(
      Long staffId,
      String prompt,
      String schemaResource,
      Image image,
      long timeoutSeconds,
      long maxReplyBytes) {}

  /** Enthält ein geprüftes Bild und die aus seinem Format abgeleitete Dateiendung */
  public record Image(byte[] content, String extension) {
    /** Hält den Bildinhalt aus der Textdarstellung für technische Logs heraus */
    @Override
    public String toString() {
      return "Image";
    }
  }
}
