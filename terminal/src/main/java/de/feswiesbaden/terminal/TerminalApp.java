package de.feswiesbaden.terminal;

import de.feswiesbaden.terminal.ui.TerminalView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class TerminalApp extends Application {

  // Absoluter Pfad, sonst sucht JavaFX im Paket der aufrufenden Klasse.
  private static final String STYLESHEET =
      TerminalApp.class.getResource("/de/feswiesbaden/terminal/terminal.css").toExternalForm();

  @Override
  public void start(Stage fenster) {
    TerminalView view = new TerminalView();

    fenster.setTitle("RFID-Terminal");
    Scene szene = new Scene(view.node(), 900, 640);
    szene.getStylesheets().add(STYLESHEET);
    fenster.setScene(szene);
    fenster.show();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
