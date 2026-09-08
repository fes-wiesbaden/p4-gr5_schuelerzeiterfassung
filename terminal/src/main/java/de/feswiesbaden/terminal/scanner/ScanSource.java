package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.util.function.Consumer;

public interface ScanSource extends AutoCloseable {

  void start(Consumer<Reading> onScan);

  @Override
  void close();
}
