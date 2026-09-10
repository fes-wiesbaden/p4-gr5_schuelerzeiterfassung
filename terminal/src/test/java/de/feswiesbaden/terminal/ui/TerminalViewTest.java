package de.feswiesbaden.terminal.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.feswiesbaden.terminal.model.ScanState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerminalViewTest {

  @BeforeAll
  static void javafxStarten() throws InterruptedException {
    CountDownLatch bereit = new CountDownLatch(1);

    try {
      Platform.startup(() -> bereit.countDown());
    } catch (IllegalStateException laeuftSchon) {
      bereit.countDown();
    }

    assertTrue(bereit.await(10, TimeUnit.SECONDS), "JavaFX ist nicht gestartet");
  }

  @Test
  void hatKeineBedienelemente() throws InterruptedException {
    Parent ansicht = baueAnsicht();

    for (Node knoten : ansicht.lookupAll("*")) {
      // Label ist zwar ein Control, kann man aber nicht bedienen.
      boolean bedienbar = knoten instanceof Control && !(knoten instanceof Label);
      assertFalse(bedienbar, "Bedienelement gefunden: " + knoten);
    }
  }

  @Test
  void zeigtImGrundzustandKarteAuflegen() throws InterruptedException {
    Parent ansicht = baueAnsicht();

    Label ueberschrift = (Label) ansicht.lookup(".headline");
    assertEquals("Karte auflegen", ueberschrift.getText());
  }

  @Test
  void kehrtNachVormerkungWiederAufBereitZurueck() throws InterruptedException {
    TerminalView[] ansicht = new TerminalView[1];
    Parent[] knoten = new Parent[1];
    CountDownLatch gebaut = new CountDownLatch(1);

    Platform.runLater(
        () -> {
          ansicht[0] = new TerminalView(Duration.millis(120));
          knoten[0] = ansicht[0].node();
          gebaut.countDown();
        });
    assertTrue(gebaut.await(10, TimeUnit.SECONDS), "Oberfläche wurde nicht gebaut");

    ansicht[0].showState(ScanState.QUEUED);
    Label ueberschrift = (Label) knoten[0].lookup(".headline");

    warteAufText(ueberschrift, "Lokal vorgemerkt");
    warteAufText(ueberschrift, "Karte auflegen");
  }

  // Die Anzeige wechselt im JavaFX-Thread, also so lange nachsehen, bis der
  // erwartete Text steht.
  private static void warteAufText(Label label, String erwartet) throws InterruptedException {
    long grenze = System.currentTimeMillis() + 5000;

    while (System.currentTimeMillis() < grenze) {
      String[] jetzt = new String[1];
      CountDownLatch gelesen = new CountDownLatch(1);
      Platform.runLater(
          () -> {
            jetzt[0] = label.getText();
            gelesen.countDown();
          });
      assertTrue(gelesen.await(5, TimeUnit.SECONDS), "Anzeige nicht lesbar");

      if (erwartet.equals(jetzt[0])) {
        return;
      }
      Thread.sleep(20);
    }

    throw new AssertionError("Anzeige zeigte nie: " + erwartet);
  }

  // JavaFX baut Oberflächen nur im eigenen Thread. Also dort bauen und warten,
  // bis sie fertig ist.
  private static Parent baueAnsicht() throws InterruptedException {
    Parent[] ergebnis = new Parent[1];
    CountDownLatch fertig = new CountDownLatch(1);

    Platform.runLater(
        () -> {
          ergebnis[0] = new TerminalView().node();
          fertig.countDown();
        });

    assertTrue(fertig.await(10, TimeUnit.SECONDS), "Oberfläche wurde nicht gebaut");
    return ergebnis[0];
  }
}
