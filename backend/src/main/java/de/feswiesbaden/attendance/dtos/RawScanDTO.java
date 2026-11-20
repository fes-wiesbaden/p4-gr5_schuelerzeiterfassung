package de.feswiesbaden.attendance.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

public record RawScanDTO(UUID scanId, UUID rfidUid, Long terminalId, LocalDateTime scannedAt) {}
