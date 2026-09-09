package de.feswiesbaden.terminal;

import de.feswiesbaden.terminal.config.TerminalConfig;
import de.feswiesbaden.terminal.model.Scan;
import de.feswiesbaden.terminal.model.ScanState;
import de.feswiesbaden.terminal.queue.ScanQueue;
import de.feswiesbaden.terminal.scanner.ConsoleScanSource;
import de.feswiesbaden.terminal.scanner.ScanSource;
import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import de.feswiesbaden.terminal.scanner.SerialScanSource;
import de.feswiesbaden.terminal.transport.DeliveryWorker;
import de.feswiesbaden.terminal.transport.ScanSender;
import de.feswiesbaden.terminal.transport.SendResult;
import de.feswiesbaden.terminal.transport.TerminalSslContext;
import de.feswiesbaden.terminal.ui.TerminalView;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class TerminalApp extends Application {
  // Absoluter Pfad, sonst sucht JavaFX im Paket der aufrufenden Klasse.
  private static final String STYLESHEET =
      TerminalApp.class.getResource("/de/feswiesbaden/terminal/terminal.css").toExternalForm();

  private ScanQueue queue;

  private ScanSource scanner;

  private DeliveryWorker worker;

  private TerminalView view;

  // Der Scan, dessen Rückmeldung gerade angezeigt werden darf. Zustellversuche
  // älterer Scans laufen im Hintergrund weiter und dürfen die Anzeige nicht
  // umschalten, sonst wechselt sie im Takt des Zustellversuchs hin und her.
  private final AtomicReference<String> angezeigterScan = new AtomicReference<>();

  @Override
  public void start(Stage fenster) {
    TerminalConfig config = TerminalConfig.load(Path.of("terminal.properties"));
    queue = new ScanQueue(config.queueFile());
    view = new TerminalView();

    ScanSender sender =
        new ScanSender(
            config.serverUrl(),
            TerminalSslContext.loadOrNull(config.clientKeystore(), config.keystorePassword()));

    worker = new DeliveryWorker(queue, sender, this::onResult);
    worker.start();

    scanner =
        config.usesConsole()
            ? new ConsoleScanSource()
            : new SerialScanSource(config.serialPort(), config.baudRate());
    scanner.start(this::onScan);

    updateQueueInfo();

    fenster.setTitle("RFID-Terminal");
    Scene szene = new Scene(view.node(), 900, 640);
    szene.getStylesheets().add(STYLESHEET);
    fenster.setScene(szene);
    fenster.show();
  }

  // Erst vormerken, dann senden. Stürzt das Programm direkt nach dem Auflegen
  // der Karte ab, ist der Scan trotzdem da.
  private void onScan(Reading gelesen) {
    Scan scan = Scan.of(gelesen.rfidUid(), gelesen.terminalNumber());
    try {
      queue.add(scan);
      angezeigterScan.set(scan.scanId());
      view.showTerminalNumber(gelesen.terminalNumber());
      view.showState(ScanState.PROCESSING);
      updateQueueInfo();
      worker.deliverNow();
    } catch (IOException nichtSpeicherbar) {
      System.err.println("Scan nicht speicherbar: " + nichtSpeicherbar.getMessage());
      view.showError("LOCAL_STORAGE_FAILED");
    }
  }

  // Jeder aufgelegte Chip bekommt genau eine sichtbare Rückmeldung. Danach ist
  // die Anzeige wieder frei für den nächsten Schüler.
  private void onResult(Scan scan, SendResult ergebnis) {
    switch (ergebnis.status()) {
      // Eine fehlende Verbindung betrifft jeden wartenden Scan. Die
      // Warteschlange arbeitet der Reihe nach, der eigene Scan kommt also
      // vielleicht gar nicht dran. Die Vormerkung gilt trotzdem für ihn.
      case RETRY -> {
        if (angezeigterScan.getAndSet(null) != null) {
          view.showState(ScanState.QUEUED);
        }
      }
      case ACCEPTED -> {
        if (angezeigterScan.compareAndSet(scan.scanId(), null)) {
          view.showState(ScanState.SUCCESS);
        }
      }
      case REJECTED -> {
        if (angezeigterScan.compareAndSet(scan.scanId(), null)) {
          view.showError(ergebnis.code());
        }
      }
    }
    updateQueueInfo();
  }

  private void updateQueueInfo() {
    try {
      view.showQueueSize(queue.size());
    } catch (IOException nichtLesbar) {
      System.err.println("Größe der Warteschlange unbekannt: " + nichtLesbar.getMessage());
    }
  }

  @Override
  public void stop() {
    if (scanner != null) {
      scanner.close();
    }
    if (worker != null) {
      worker.close();
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
