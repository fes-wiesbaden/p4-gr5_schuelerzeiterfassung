package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.TerminalRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prüft die Terminalidentität und ermittelt den Raum */
@Service
public class TerminalIdentityService {
  private final TerminalRepository terminals;

  /** Übernimmt das Repository für die Raumzuordnung */
  public TerminalIdentityService(TerminalRepository terminals) {
    this.terminals = terminals;
  }

  /** Vergleicht die gemeldete Terminalnummer mit dem Zertifikat und liest die Raum-ID */
  @Transactional
  public Long resolveRoom(String verifiedSubject, int terminalId) {
    if (terminalId <= 0 || !("CN=terminal-" + terminalId).equals(verifiedSubject)) {
      throw new AccessDeniedException("SCAN_REJECTED");
    }

    return terminals
        .findRoomId(terminalId)
        .orElseThrow(() -> new AccessDeniedException("SCAN_REJECTED"));
  }
}
