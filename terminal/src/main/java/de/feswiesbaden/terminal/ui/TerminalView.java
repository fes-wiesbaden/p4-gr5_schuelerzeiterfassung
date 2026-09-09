package de.feswiesbaden.terminal.ui;

import de.feswiesbaden.terminal.model.ScanState;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

// Die Oberfläche zeigt nur an. Entschieden wird alles auf dem Server.
public final class TerminalView {
  private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm");

  private static final Duration RUECKKEHR_NACH_BEREIT = Duration.seconds(3);

  private final BorderPane root = new BorderPane();

  private final VBox stage = new VBox();

  private final Label symbol = new Label();

  private final Label headline = new Label();

  private final Label hint = new Label();

  private final Label badge = new Label();

  private final Label queueInfo = new Label();

  private final Label terminalInfo = new Label();

  private final PauseTransition zurueckAufBereit;

  public TerminalView() {
    this(RUECKKEHR_NACH_BEREIT);
  }

  // Nur für Tests, damit die nicht drei Sekunden lang warten muessen.
  TerminalView(Duration rueckkehr) {
    zurueckAufBereit = new PauseTransition(rueckkehr);
    root.getStyleClass().add("root");
    root.setTop(header());
    root.setCenter(stageBox());
    root.setBottom(footer());

    zurueckAufBereit.setOnFinished(fertig -> show(ScanState.READY));
    show(ScanState.READY);
  }

  public BorderPane node() {
    return root;
  }

  // Jede Rückmeldung läuft nach 3 Sekunden ab, der Grundzustand ist immer
  // "bereit". Sonst klebte die Rückmeldung des Vorgängers am Schirm und der
  // nächste Schüler sähe nicht, ob seine eigene Karte gelesen wurde. Wie viele
  // Scans wirklich offen sind, steht dauerhaft in der Fußzeile.
  public void showState(ScanState state) {
    Platform.runLater(
        () -> {
          show(state);
          zurueckAufBereit.stop();
          if (state != ScanState.READY) {
            zurueckAufBereit.playFromStart();
          }
        });
  }

  // Der öffentliche Code darf angezeigt werden, der wirkliche Grund nie (#37).
  public void showError(String code) {
    Platform.runLater(
        () -> {
          show(ScanState.ERROR);
          hint.setText(ScanState.ERROR.hint() + "  (" + code + ")");
          zurueckAufBereit.stop();
          zurueckAufBereit.playFromStart();
        });
  }

  public void showTerminalNumber(int nummer) {
    Platform.runLater(() -> terminalInfo.setText("Terminal " + nummer));
  }

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

  private void show(ScanState state) {
    symbol.setText(state.symbol());
    headline.setText(state.headline());
    hint.setText(state.hint());
    if (state == ScanState.READY) {
      badge.setText("Bereit · " + LocalTime.now().format(UHRZEIT) + " Uhr");
    } else if (state == ScanState.PROCESSING) {
      badge.setText("Bitte warten");
    } else if (state == ScanState.QUEUED) {
      badge.setText("Wird nachgereicht");
    } else {
      badge.setText("Zurück zu „Bereit“ in 3 s");
    }

    stage.getStyleClass().removeIf(klasse -> klasse.startsWith("state-"));
    stage.getStyleClass().add("state-" + state.name().toLowerCase());
  }

  private HBox header() {
    Label titel = new Label("Anwesenheitserfassung");
    titel.getStyleClass().add("header-title");

    HBox leiste = new HBox(titel);
    leiste.getStyleClass().add("header");
    leiste.setAlignment(Pos.CENTER_LEFT);
    return leiste;
  }

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
