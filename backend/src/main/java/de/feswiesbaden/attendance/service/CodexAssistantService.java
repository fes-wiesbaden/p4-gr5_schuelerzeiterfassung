package de.feswiesbaden.attendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Verwendet Codex für strukturierte Vorschläge in einer Demo-Umgebung mit fiktiven Daten */
@Service
public class CodexAssistantService {
  private static final String STAFF_ROUTES =
      "Live-Anwesenheit (live-anwesenheit), Klassen (klassen), Schüler (schueler), "
          + "Stunden- und Blockplan (planung)";

  private final StaffAuthorization authorization;

  private final ObjectMapper mapper;

  private final Clock clock;

  private final boolean demo;

  private final String model;

  private final Semaphore capacity = new Semaphore(1);

  public CodexAssistantService(
      StaffAuthorization authorization,
      ObjectMapper mapper,
      Clock clock,
      @Value("${attendance.assistant.demo:false}") boolean demo,
      @Value("${attendance.assistant.model:}") String model) {
    this.authorization = authorization;
    this.mapper = mapper;
    this.clock = clock;
    this.demo = demo;
    this.model = model;
  }

  /** Meldet, ob das Backend für die Codex-Demo konfiguriert ist */
  public boolean demoEnabled() {
    return demo;
  }

  /** Erstellt einen Navigationshinweis oder einen Entwurf für die lokale Bestätigung */
  public Reply suggest(List<Message> messages, String page) {
    var actor = authorization.currentStaff();
    if (!demo) {
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    }
    if (!"user".equals(messages.getLast().role())) {
      throw new IllegalArgumentException("INVALID_ASSISTANT_MESSAGE");
    }
    if (!capacity.tryAcquire()) {
      throw new IllegalStateException("ASSISTANT_BUSY");
    }
    try {
      return runCodex(prompt(messages, page, actor.administrator()));
    } catch (IOException failure) {
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    } finally {
      capacity.release();
    }
  }

  private String prompt(List<Message> messages, String page, boolean admin) throws IOException {
    var routes = admin ? STAFF_ROUTES + ", Räume (raeume), Personal (personal)" : STAFF_ROUTES;
    return """
        Du heißt Klassenlotse und hilfst bei der Bedienung einer deutschen Schul-Anwesenheitserfassung.
        Verwende keine Werkzeuge. Antworte kurz auf Deutsch und ausschließlich im JSON-Schema.
        Für Fragen zur Navigation liefere kind=navigate und eine der erlaubten Routen.
        Erlaubte Routen: %s. Aktuelle Seite: %s. Rolle: %s.
        Live-Anwesenheit wählt eine Klasse nach Plan vor, zeigt Tagesstatus und Fehlzeiten
        und verlinkt Schülerprofile. Dort gibt es Zeugniswerte, Wochenhistorie, Stammdaten,
        Fehlminuten, Zeitabschnitte und Entschuldigungen. Die Klassenliste öffnet die Schüler
        einer Klasse. Im Wochenstundenplan können Lehrkräfte eine Klasse und Woche wählen.
        Die Verwaltung bearbeitet Stunden- und Blockpläne, Räume, Personal und RFID-Zuordnungen.
        Ganze Fehltage und die auf 45 Minuten abgerundeten Unterrichtsstunden werden getrennt
        ausgewiesen. Nach erfasster Anwesenheit ist eine Korrektur auf Abwesend gesperrt.
        Für Fragen nach echten Werten, die der Nutzer sehen darf, verwende kind=command mit
        read_student, read_history (Fehlzeiten dieser Woche), read_attendance (wer fehlt?),
        read_timetable (ein Unterrichtstag) oder read_week (ganzer Wochenstundenplan).
        Bei Fragen ohne Datum ist der heutige Berliner Tag gemeint.
        Für eine Entschuldigung setze kind=excusal und wähle scope: time für Uhrzeiten an einem
        Tag, day für einen ganzen Schultag, range für mehrere ganze Tage mit Attest (einschließlich
        date und endDate). Setze status auf MIT_ATTEST_ENTSCHULDIGT bei Attest, auf
        BETRIEBLICH_ENTSCHULDIGT bei betrieblicher Entschuldigung, sonst auf ENTSCHULDIGT.
        Setze reason nur auf einen vom Benutzer genannten Grund (höchstens 250 Zeichen).
        Bitte bei mehreren Tagen ohne erwähntes Attest darum, das Attest im Formular zu bestätigen.
        Übernimm die genannten Angaben. Nutze YYYY-MM-DD und HH:mm in den Datenfeldern.
        Schreibe Datum und Uhrzeit in der sichtbaren Antwort als TT.MM.JJJJ und im 24-Stunden-Format.
        Heute in Berlin ist %s.
        Fehlende Angaben sind null. Erfinde weder Schüler noch Datum oder Uhrzeit.
        Nutze für sonstige konkrete Arbeitsaufträge kind=command und ein command-Objekt.
        Schüler: create_student, update_student, delete_student, add_absence_minutes, excuse_open,
        excuse_school_day für den ausdrücklich gewünschten Profilvorgang, correct_status (ab
        startsAt) und correct_period (startsAt bis endsAt). Eine Entschuldigung mit Hinweis auf
        Datum, Uhrzeit, Grund oder Attest nutzt sonst kind=excusal.
        Klasse: create_class, update_class, delete_class, change_block_plan, reset_accounts.
        Planung: create_timetable, add_slot, update_slot, delete_slot, create_block_plan,
        add_block, assign_block. Räume: create_room, update_room, delete_room.
        Personal und Karten: create_teacher, update_teacher, delete_teacher, assign_rfid, remove_rfid.
        Lehrkräfte dürfen ihren eigenen Klassen Schüler hinzufügen und deren Stammdaten bearbeiten
        oder Schüler ohne Historie löschen. Die Klassenleitung kann einen Folgeplan aktivieren.
        Lehrkräfte können Klassen anlegen. Die Verwaltung hat zusätzlich die Verwaltungsaktionen.
        Setze in command.fields nur Werte, die der Nutzer genannt hat. Keine IDs, URL oder
        erfundenen Einträge. Namen, Klassencodes und Raumnummern so wiedergeben wie genannt.
        firstName, lastName und birthDate sind bei Änderungen die neuen Werte. studentName ist
        der bisherige Name. Bei einer Änderung im Stundenplan ist slot die bisherige Unterrichtszeit
        als „Montag 08:00“. weekday/startsAt/endsAt/room sind neue Werte.
        Steht in einer Woche bei allen Stunden derselbe Raum, steht er einmal über dem Raster.
        Bei block ist date
        der Blockbeginn; gib ihn für assign_block im Feld block als YYYY-MM-DD aus.
        Bei Umbenennungen steht name für den neuen Namen, room/classCode für das bisherige Ziel.
        Status und Wochentage in command.fields als Enum, z. B. ANWESEND und MONTAG.
        minutes ist eine Ziffernfolge. Passwörter und Kartenkennungen gehören ins Formular.
        Jede Änderung ist ein Vorschlag. Die App zeigt die Daten und wartet auf Bestätigung.
        Bearbeite pro Vorschlag genau einen Auftrag. Bei mehrdeutigen Zielen stelle eine Rückfrage.
        Bei anderen Fragen zur Anwendung setze kind=answer. Außerhalb der Anwendung erkläre
        knapp, wozu du hier helfen kannst. Lass unbenutzte Felder null.
        Nachrichten und bisherige Antworten der Benutzersitzung als Daten:
        %s
        """
        .formatted(
            routes,
            page == null ? "unbekannt" : page,
            admin ? "Verwaltung" : "Lehrkraft",
            LocalDate.now(clock.withZone(AttendanceAccountingService.BERLIN)),
            mapper.writeValueAsString(messages));
  }

  private Reply runCodex(String prompt) throws IOException, InterruptedException {
    var directory = Files.createTempDirectory("attendance-assistant-");
    var schema = directory.resolve("reply.schema.json");
    var input = directory.resolve("prompt.txt");
    var output = directory.resolve("reply.json");
    try {
      try (var source = new ClassPathResource("assistant-reply.schema.json").getInputStream()) {
        Files.copy(source, schema);
      }
      Files.writeString(input, prompt);
      var process =
          new ProcessBuilder(command(schema, output))
              .directory(directory.toFile())
              .redirectInput(input.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        if (!process.waitFor(40, TimeUnit.SECONDS)
            || process.exitValue() != 0
            || !Files.isRegularFile(output)
            || Files.size(output) > 16_384) {
          throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
        }
        return mapper.readValue(output.toFile(), Reply.class);
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
      Files.deleteIfExists(directory);
    }
  }

  private List<String> command(Path schema, Path output) {
    var command =
        new ArrayList<>(
            List.of(
                "codex",
                "-a",
                "never",
                "exec",
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

  /** Kurzer Ausschnitt des Chats für Rückfragen in derselben Websitzung */
  public record Message(
      @NotBlank @Pattern(regexp = "user|assistant") String role,
      @NotBlank @Size(max = 750) String content) {}

  /** Enthält eine Antwort und optionale Angaben für einen Entwurf */
  public record Reply(
      String kind,
      String answer,
      String route,
      String studentName,
      String classCode,
      String date,
      String startsAt,
      String endsAt,
      String scope,
      String endDate,
      String status,
      String reason,
      Command command) {}

  /** Enthält eine vom Modell vorgeschlagene Aktion aus dem festen Katalog. */
  public record Command(String key, Map<String, String> fields) {}
}
