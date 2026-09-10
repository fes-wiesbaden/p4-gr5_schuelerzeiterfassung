package de.feswiesbaden.terminal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

// Clientzertifikat für mTLS aus einer PKCS12-Datei; nginx prüft es gegen die
// Terminal-Client-CA. Für das Serverzertifikat gilt bewusst die
// JVM-Standardprüfung gegen den lokalen Server-Truststore (#26, #29).
public final class TerminalSslContext {

  private TerminalSslContext() {}

  public static SSLContext load(
      Path keystore, String keystorePassword, Path truststore, String truststorePassword) {
    if (keystore == null || !Files.isReadable(keystore)) {
      throw new IllegalStateException("Clientzertifikat fehlt oder ist nicht lesbar.");
    }
    if (truststore == null || !Files.isReadable(truststore)) {
      throw new IllegalStateException("Server-Truststore fehlt oder ist nicht lesbar.");
    }

    try (InputStream keyInput = Files.newInputStream(keystore);
        InputStream trustInput = Files.newInputStream(truststore)) {
      KeyStore speicher = KeyStore.getInstance("PKCS12");
      speicher.load(keyInput, keystorePassword.toCharArray());

      KeyManagerFactory schluessel =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      schluessel.init(speicher, keystorePassword.toCharArray());

      KeyStore vertrauen = KeyStore.getInstance("PKCS12");
      vertrauen.load(trustInput, truststorePassword.toCharArray());
      TrustManagerFactory trustManagers =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(vertrauen);

      SSLContext context = SSLContext.getInstance("TLS");
      context.init(schluessel.getKeyManagers(), trustManagers.getTrustManagers(), null);
      return context;

    } catch (IOException | GeneralSecurityException fehler) {
      throw new IllegalStateException("TLS-Konfiguration nicht ladbar.", fehler);
    }
  }
}
