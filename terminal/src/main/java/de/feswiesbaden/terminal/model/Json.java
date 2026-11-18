package de.feswiesbaden.terminal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Stellt gemeinsame JSON-Einstellungen mit ISO-8601-Zeitpunkten für Empfang, Queue und Versand
 * bereit
 */
public final class Json {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private Json() {}

  /** Liefert den gemeinsam konfigurierten JSON-Mapper */
  public static ObjectMapper mapper() {
    return MAPPER;
  }
}
