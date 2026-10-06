package de.feswiesbaden.terminal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

// Eine einzige Jackson-Einstellung für Puffer, Serverantwort und ESP32-Zeile.
// Zeitpunkte werden als ISO-8601 geschrieben, damit der Puffer lesbar bleibt
// und der Server dieselbe Schreibweise bekommt.
public final class Json {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private Json() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }
}
