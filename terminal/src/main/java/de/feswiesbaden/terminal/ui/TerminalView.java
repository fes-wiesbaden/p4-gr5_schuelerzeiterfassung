package de.feswiesbaden.terminal.ui;

import de.feswiesbaden.terminal.model.ScanState;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Zeigt den Scanstatus ohne personenbezogene Daten oder Bedienelemente an */
public final class TerminalView {
  private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm");

  private static final Duration RUECKKEHR_NACH_BEREIT = Duration.seconds(2);

  // "Wird verarbeitet" würde sonst nur aufblitzen: die Antwort kommt oft schon
  // nach gut hundert Millisekunden
  private static final Duration MINDESTENS_VERARBEITEN = Duration.seconds(1);

  private final BorderPane root = new BorderPane();

  private final VBox stage = new VBox();

  private final Label symbol = new Label();

  private final Label headline = new Label();

  private final Label hint = new Label();

  private final Label badge = new Label();

  private final Label queueInfo = new Label();

  private final Label terminalInfo = new Label();

  private final PauseTransition zurueckAufBereit;

  private final PauseTransition verarbeitenLaeuft = new PauseTransition(MINDESTENS_VERARBEITEN);

  private ScanState naechsterZustand;

  private String naechsterCode;

  /** Erstellt die Statusanzeige mit zwei Sekunden Rückkehrzeit zur Bereitschaft */
  public TerminalView() {
    this(RUECKKEHR_NACH_BEREIT);
  }

  /** Erstellt die Statusanzeige mit einstellbarer Rückkehrzeit für Tests */
  TerminalView(Duration rueckkehr) {
    zurueckAufBereit = new PauseTransition(rueckkehr);
    root.getStyleClass().add("root");
    root.setTop(header());
    root.setCenter(stageBox());
    root.setBottom(footer());

    zurueckAufBereit.setOnFinished(fertig -> show(ScanState.READY));
    verarbeitenLaeuft.setOnFinished(fertig -> zeigeGemerkten());
    show(ScanState.READY);
  }

  /** Liefert den Wurzelknoten der Terminalansicht zum Einfügen in eine JavaFX-Szene */
  public BorderPane node() {
    return root;
  }

  /**
   * Plant den Statuswechsel im JavaFX-Thread ein. Jeder Zustand außer READY startet eine
   * automatische Rückkehr zur Bereitschaft nach der eingestellten Rückkehrzeit
   */
  public void showState(ScanState state) {
    Platform.runLater(() -> zeige(state, null));
  }

  /**
   * Zeigt LOCAL_STORAGE_FAILED unverändert an und ersetzt jeden anderen Fehlercode durch
   * SCAN_REJECTED
   */
  public void showError(String code) {
    String neutralCode = "LOCAL_STORAGE_FAILED".equals(code) ? code : "SCAN_REJECTED";
    Platform.runLater(() -> zeige(ScanState.ERROR, neutralCode));
  }

  /** Zeigt die Verarbeitung mindestens eine Sekunde und merkt früh eintreffende Ergebnisse vor */
  private void zeige(ScanState state, String code) {
    if (state == ScanState.PROCESSING) {
      naechsterZustand = null;
      anzeigen(state, code);
      verarbeitenLaeuft.playFromStart();
      return;
    }

    if (verarbeitenLaeuft.getStatus() == Animation.Status.RUNNING) {
      naechsterZustand = state;
      naechsterCode = code;
      return;
    }

    anzeigen(state, code);
  }

  /** Zeigt das vorgemerkte Ergebnis nach Ablauf der Mindestanzeigezeit */
  private void zeigeGemerkten() {
    if (naechsterZustand == null) {
      return;
    }
    ScanState state = naechsterZustand;
    String code = naechsterCode;
    naechsterZustand = null;
    anzeigen(state, code);
  }

  /** Zeigt Status und Fehlercode an und startet die Rückkehr zur Bereitschaft */
  private void anzeigen(ScanState state, String code) {
    show(state);
    if (code != null) {
      hint.setText(state.hint() + "  (" + code + ")");
    }

    zurueckAufBereit.stop();
    if (state != ScanState.READY) {
      zurueckAufBereit.playFromStart();
    }
  }

  /** Aktualisiert die Terminalnummer im JavaFX-Thread */
  public void showTerminalNumber(int nummer) {
    Platform.runLater(() -> terminalInfo.setText("Terminal " + nummer));
  }

  /** Zeigt die Zahl der noch nicht zugestellten Scans an */
  public void showQueueSize(int offen) {
    String text;
    if (offen == 0) {
      text = "keine offenen Scans";
    } else if (offen == 1) {
      text = "1 Scan wartet auf Zustellung";
    } else {
      text = offen + " Scans warten auf Zustellung";
    }
    Platform.runLater(() -> queueInfo.setText(text));
  }

  /** Setzt Texte, Symbol und Gestaltung für den gewählten Status */
  private void show(ScanState state) {
    symbol.setText(state.symbol());
    headline.setText(state.headline());
    hint.setText(state.hint());
    badge.setText(
        switch (state) {
          case READY -> "Bereit · " + LocalTime.now().format(UHRZEIT) + " Uhr";
          case PROCESSING -> "Bitte warten";
          case QUEUED -> "Wird nachgereicht";
          case SUCCESS, ALREADY_RECEIVED, ERROR ->
              "Zurück zu „Bereit“ in " + (int) RUECKKEHR_NACH_BEREIT.toSeconds() + " s";
        });

    stage.getStyleClass().removeIf(klasse -> klasse.startsWith("state-"));
    stage.getStyleClass().add("state-" + state.name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }

  /** Baut die Kopfzeile mit dem Titel der Anwendung */
  private HBox header() {
    Label titel = new Label("Anwesenheitserfassung");
    titel.getStyleClass().add("header-title");

    HBox leiste = new HBox(titel);
    leiste.getStyleClass().add("header");
    leiste.setAlignment(Pos.CENTER_LEFT);
    return leiste;
  }

  /** Baut den Anzeigebereich für Symbol, Status und Hinweis */
  private VBox stageBox() {
    symbol.getStyleClass().add("symbol");
    headline.getStyleClass().add("headline");
    hint.getStyleClass().add("hint");
    badge.getStyleClass().add("badge");

    stage.getStyleClass().add("stage-box");
    stage.setAlignment(Pos.CENTER);
    stage.getChildren().addAll(symbol, headline, hint, badge);
    return stage;
  }

  /** Baut die Fußzeile mit Terminalnummer und offenen Scans */
  private HBox footer() {
    terminalInfo.setText("Terminal —");
    terminalInfo.getStyleClass().add("footer-text");
    queueInfo.getStyleClass().add("footer-strong");

    Region luecke = new Region();
    HBox.setHgrow(luecke, Priority.ALWAYS);

    HBox leiste = new HBox(terminalInfo, luecke, queueInfo);
    leiste.getStyleClass().add("footer");
    leiste.setAlignment(Pos.CENTER_LEFT);
    return leiste;
  }
}
