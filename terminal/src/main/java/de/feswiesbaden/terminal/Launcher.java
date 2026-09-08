package de.feswiesbaden.terminal;

// Startpunkt zum Ausprobieren aus der Entwicklungsumgebung heraus.
//
// Erbt bewusst NICHT von javafx.application.Application: sonst verlangt Java
// beim Start einen --module-path mit den JavaFX-Modulen und bricht sonst mit
// "JavaFX-Runtime-Komponenten fehlen" ab. Über diesen Umweg genügt der normale
// Klassenpfad, den VS Code und IntelliJ von selbst aus Maven zusammenbauen.
public final class Launcher {

  private Launcher() {}

  public static void main(String[] args) {
    TerminalApp.main(args);
  }
}
