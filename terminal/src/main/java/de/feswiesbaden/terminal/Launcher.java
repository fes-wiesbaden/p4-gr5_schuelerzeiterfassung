package de.feswiesbaden.terminal;

/** Startet JavaFX ohne eigene Application-Vererbung, damit der normale Klassenpfad genügt */
public final class Launcher {

  private Launcher() {}

  /** Übergibt die Startargumente an die JavaFX-Terminalanwendung */
  public static void main(String[] args) {
    TerminalApp.main(args);
  }
}
