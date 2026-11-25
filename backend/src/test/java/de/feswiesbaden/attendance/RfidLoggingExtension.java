package de.feswiesbaden.attendance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/** Aktiviert die Logstufen für einen RFID-Test und stellt sie danach wieder her */
final class RfidLoggingExtension implements BeforeEachCallback, AfterEachCallback {
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(RfidLoggingExtension.class);

  private final Logger mvc =
      (Logger) LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation");

  private final Logger jdbc =
      (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");

  @Override
  public void beforeEach(ExtensionContext context) {
    context
        .getStore(NAMESPACE)
        .put(LogLevels.class, new LogLevels(mvc.getLevel(), jdbc.getLevel()));
    mvc.setLevel(Level.DEBUG);
    jdbc.setLevel(Level.TRACE);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    var previous = context.getStore(NAMESPACE).remove(LogLevels.class, LogLevels.class);
    mvc.setLevel(previous.mvc());
    jdbc.setLevel(previous.jdbc());
  }

  private record LogLevels(Level mvc, Level jdbc) {}
}
