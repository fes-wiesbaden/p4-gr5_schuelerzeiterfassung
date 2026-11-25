package de.feswiesbaden.attendance.service;

/** Meldet eine Blockzuordnung der Klasse zu einem anderen als ihrem aktuellen Blockplan */
public class InvalidClassBlockAssignmentException extends IllegalStateException {
  public InvalidClassBlockAssignmentException() {
    super("INVALID_CLASS_BLOCK_ASSIGNMENT");
  }
}
