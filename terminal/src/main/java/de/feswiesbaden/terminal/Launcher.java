package de.feswiesbaden.terminal;

// Erbt bewusst nicht von javafx.application.Application. Sonst verlangt Java
// beim Start einen --module-path und bricht mit "JavaFX-Runtime-Komponenten
// fehlen" ab; über diesen Umweg genügt der normale Klassenpfad.
public final class Launcher {

  private Launcher() {}

  public static void main(String[] args) {
    TerminalApp.main(args);
  }
}
