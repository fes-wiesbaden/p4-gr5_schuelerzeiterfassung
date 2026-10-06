package de.feswiesbaden.terminal.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.feswiesbaden.terminal.scanner.SerialLine.Reading;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SerialLineTest {

  @Test
  void liestUidUndTerminalnummerAusEinerJsonZeile() {
    Optional<Reading> gelesen = SerialLine.parse("{\"rfidUid\":\"04A3B2C1\",\"terminalNumber\":3}");

    assertTrue(gelesen.isPresent());
    assertEquals("04A3B2C1", gelesen.get().rfidUid());
    assertEquals(3, gelesen.get().terminalNumber());
  }

  // Der ESP32 meldet beim Start eine Zeile mit "#". Die darf nicht als Scan
  // durchgehen.
  @Test
  void verwirftStartmeldungUndMuell() {
    assertTrue(SerialLine.parse("# RFID-Leser bereit").isEmpty());
    assertTrue(SerialLine.parse("UID:04A3B2C1").isEmpty());
    assertTrue(SerialLine.parse("").isEmpty());
    assertTrue(SerialLine.parse(null).isEmpty());
  }

  @Test
  void verwirftZeileOhneUid() {
    assertTrue(SerialLine.parse("{\"terminalNumber\":3}").isEmpty());
    assertTrue(SerialLine.parse("{\"rfidUid\":\"\",\"terminalNumber\":3}").isEmpty());
  }

  @Test
  void stoertSichNichtAnLeerzeichen() {
    assertTrue(SerialLine.parse("  {\"rfidUid\":\"AB\",\"terminalNumber\":1}  ").isPresent());
  }
}
