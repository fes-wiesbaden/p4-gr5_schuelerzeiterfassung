package de.feswiesbaden.terminal.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerminalViewTest {

  @BeforeAll
  static void javafxStarten() throws InterruptedException {
    CountDownLatch bereit = new CountDownLatch(1);
    try {
      Platform.startup(bereit::countDown);
    } catch (IllegalStateException laeuftSchon) {
      bereit.countDown();
    }
    assertTrue(bereit.await(10, TimeUnit.SECONDS), "JavaFX ist nicht gestartet");
  }

  @Test
  void hatKeineBedienelemente() throws Exception {
    TerminalView view = aufDemFxThread(TerminalView::new);

    List<Node> bedienbar = new ArrayList<>();
    sammleBedienelemente(view.node(), bedienbar);

    assertEquals(List.of(), bedienbar, "Die Terminalansicht darf nichts Bedienbares enthalten");
  }

  @Test
  void zeigtImGrundzustandKeineUidUndKeinenNamen() throws Exception {
    TerminalView view = aufDemFxThread(TerminalView::new);

    String text = allerText(view.node());
    assertTrue(text.contains("Karte auflegen"), "Grundzustand fehlt");
    assertTrue(text.contains("Terminal"), "Fußzeile fehlt");
  }

  private static void sammleBedienelemente(Node knoten, List<Node> treffer) {
    if (knoten instanceof ButtonBase
        || knoten instanceof TextInputControl
        || knoten instanceof ComboBoxBase<?>) {
      treffer.add(knoten);
    }
    if (knoten instanceof Parent eltern) {
      eltern.getChildrenUnmodifiable().forEach(kind -> sammleBedienelemente(kind, treffer));
    }
  }

  private static String allerText(Node knoten) {
    StringBuilder alles = new StringBuilder();
    if (knoten instanceof javafx.scene.control.Labeled beschriftet) {
      alles.append(beschriftet.getText()).append(' ');
    }
    if (knoten instanceof Parent eltern) {
      eltern.getChildrenUnmodifiable().forEach(kind -> alles.append(allerText(kind)));
    }
    return alles.toString();
  }

  private static <T> T aufDemFxThread(java.util.function.Supplier<T> bauen) throws Exception {
    List<T> ergebnis = new ArrayList<>();
    CountDownLatch fertig = new CountDownLatch(1);
    Platform.runLater(
        () -> {
          ergebnis.add(bauen.get());
          fertig.countDown();
        });
    assertTrue(fertig.await(10, TimeUnit.SECONDS), "Oberfläche wurde nicht gebaut");
    return ergebnis.get(0);
  }
}
