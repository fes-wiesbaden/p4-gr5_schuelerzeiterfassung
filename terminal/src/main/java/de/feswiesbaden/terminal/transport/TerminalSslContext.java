package de.feswiesbaden.terminal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

// Clientzertifikat für mTLS aus einer PKCS12-Datei; nginx prüft es gegen die
// Terminal-Client-CA. Für das Serverzertifikat gilt bewusst die
// JVM-Standardprüfung gegen den installierten Truststore (#26, #29).
public final class TerminalSslContext {

  private TerminalSslContext() {}

  // Ohne Schlüsselspeicher läuft die Anwendung ohne mTLS weiter, das Backend
  // weist sie dann ab.
  public static SSLContext loadOrNull(Path keystore, String password) {
    if (keystore == null || !Files.isReadable(keystore)) {
      return null;
    }

    try (InputStream in = Files.newInputStream(keystore)) {
      KeyStore speicher = KeyStore.getInstance("PKCS12");
      speicher.load(in, password.toCharArray());

      KeyManagerFactory schluessel =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      schluessel.init(speicher, password.toCharArray());

      SSLContext context = SSLContext.getInstance("TLS");
      context.init(schluessel.getKeyManagers(), null, null);
      return context;

    } catch (IOException | GeneralSecurityException fehler) {
      System.err.println("Clientzertifikat nicht ladbar: " + fehler.getMessage());
      return null;
    }
  }
}
