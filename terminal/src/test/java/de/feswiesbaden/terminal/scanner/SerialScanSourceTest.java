package de.feswiesbaden.terminal.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fazecast.jSerialComm.SerialPortTimeoutException;
import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SerialScanSourceTest {
  @Test
  void liestGeteilteJsonZeileNachEinemTimeoutWeiter() throws IOException {
    var data =
        new ByteArrayInputStream(
            "{\"rfidUid\":\"AABBCCDD\",\"terminalNumber\":29}\r\n"
                .getBytes(StandardCharsets.UTF_8));
    var serialInput =
        new FilterInputStream(data) {
          private int reads;

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            reads++;
            if (reads == 2) {
              throw new SerialPortTimeoutException("Lesepause");
            }
            return super.read(buffer, offset, reads == 1 ? Math.min(length, 16) : length);
          }
        };
    List<Reading> scans = new ArrayList<>();

    SerialScanSource.readLines(serialInput, () -> true, scans::add);

    assertEquals(List.of(new Reading("AABBCCDD", 29)), scans);
  }
}
