package de.feswiesbaden.attendance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

  private final CodexRunner runner;

  public CodexAssistantService(
      StaffAuthorization authorization, ObjectMapper mapper, Clock clock, CodexRunner runner) {
    this.authorization = authorization;
    this.mapper = mapper;
    this.clock = clock;
    this.runner = runner;
  }

  /** Meldet, ob das Backend für die Codex-Demo konfiguriert ist */
  public boolean demoEnabled() {
    return runner.enabled();
  }

  /** Erstellt einen Navigationshinweis oder einen Entwurf für die lokale Bestätigung */
  public Reply suggest(List<Message> messages, String page) {
    var actor = authorization.currentStaff();
    if (!runner.enabled()) {
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
    }
    // Leere Verlaufseinträge, etwa abgebrochene Antworten, werden übergangen
    var history =
        messages.stream()
            .filter(message -> message.content() != null && !message.content().isBlank())
            .toList();
    if (history.isEmpty() || !"user".equals(history.getLast().role())) {
      throw new IllegalArgumentException("INVALID_ASSISTANT_MESSAGE");
    }
    try {
      return mapper.readValue(
          runner.run(
              new CodexRunner.Request(
                  actor.id(),
                  prompt(history, page, actor.administrator()),
                  "assistant-reply.schema.json",
                  null,
                  40,
                  16_384)),
          Reply.class);
    } catch (IOException failure) {
      throw new IllegalStateException("ASSISTANT_UNAVAILABLE");
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
        und verlinkt Schülerprofile. Den Status korrigiert man dort mit „Status ändern“; er gilt
        ab der eingetragenen Uhrzeit. Das Schülerprofil zeigt Zeugniswerte, Wochenhistorie und
        Stammdaten; über „Korrigieren“ am jeweiligen Tag lassen sich Fehlminuten eintragen,
        der Status für einen Zeitraum ändern und Fehlzeiten entschuldigen.
        Die Klassenliste öffnet die Schüler einer Klasse. Im Wochenstundenplan wählen Lehrkräfte
        Klasse und Woche; ein Klick auf eine Stunde trägt Unterrichtsausfall für diesen Tag ein.
        Die Verwaltung bearbeitet Stunden- und Blockpläne, Räume, Lehrkräfte und RFID-Zuordnungen.
        Ganze Fehltage und die auf 45 Minuten abgerundeten Unterrichtsstunden werden getrennt
        ausgewiesen. Nach einem Kartenscan ist eine Korrektur auf Abwesend gesperrt.
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
        Bei add_absence_minutes ist note der Vermerk zur Buchung: Übernimm einen vom Nutzer
        genannten Grund (höchstens 200 Zeichen), sonst bleibt note null.
        Jede Änderung ist ein Vorschlag. Die App zeigt die Daten und wartet auf Bestätigung.
        Bearbeite pro Vorschlag genau einen Auftrag. Bei mehrdeutigen Zielen stelle eine Rückfrage.
        Enthält eine Nachricht mehrere Teilfragen, beantworte in answer alle, die sich ohne
        Datenabruf und ohne Vorschlag beantworten lassen. Braucht eine Teilfrage kind=command,
        kind=excusal oder kind=navigate, bearbeite die erste davon und nenne in answer
        ausdrücklich, welche weitere Teilfrage der Nutzer danach als eigene Nachricht stellen
        soll. answer ist nie leer und nie eine bloße Ankündigung wie „Ich sehe nach“.
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

  /** Kurzer Ausschnitt des Chats für Rückfragen in derselben Websitzung */
  public record Message(
      @NotBlank @Pattern(regexp = "user|assistant") String role, @Size(max = 750) String content) {}

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
