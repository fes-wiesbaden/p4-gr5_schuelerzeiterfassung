package de.feswiesbaden.terminal;

import de.feswiesbaden.terminal.config.TerminalConfig;
import de.feswiesbaden.terminal.model.ScanState;
import de.feswiesbaden.terminal.scanner.ConsoleScanSource;
import de.feswiesbaden.terminal.scanner.ScanSource;
import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import de.feswiesbaden.terminal.scanner.SerialScanSource;
import de.feswiesbaden.terminal.ui.TerminalView;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class TerminalApp extends Application {

  // Absoluter Pfad, sonst sucht JavaFX im Paket der aufrufenden Klasse.
  private static final String STYLESHEET =
      TerminalApp.class.getResource("/de/feswiesbaden/terminal/terminal.css").toExternalForm();

  private ScanSource scanner;

  private TerminalView view;

  @Override
  public void start(Stage fenster) {
    TerminalConfig config = TerminalConfig.load(Path.of("terminal.properties"));
    view = new TerminalView();

    scanner =
        config.usesConsole()
            ? new ConsoleScanSource()
            : new SerialScanSource(config.serialPort(), config.baudRate());
    scanner.start(this::onScan);

    fenster.setTitle("RFID-Terminal");
    Scene szene = new Scene(view.node(), 900, 640);
    szene.getStylesheets().add(STYLESHEET);
    fenster.setScene(szene);
    fenster.show();
  }

  private void onScan(Reading gelesen) {
    view.showTerminalNumber(gelesen.terminalNumber());
    view.showState(ScanState.PROCESSING);
  }

  @Override
  public void stop() {
    if (scanner != null) {
      scanner.close();
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
