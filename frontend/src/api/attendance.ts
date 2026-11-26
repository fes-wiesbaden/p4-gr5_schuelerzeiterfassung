// Tagesstatus wie im Backend-Enum AttendanceStatus
export const STATUS_LABELS = {
  ANWESEND: 'Anwesend',
  ABWESEND: 'Abwesend',
  BETRIEB: 'Im Betrieb',
  BETRIEBLICH_ENTSCHULDIGT: 'Betrieblich entschuldigt',
  ENTSCHULDIGT: 'Entschuldigt',
  MIT_ATTEST_ENTSCHULDIGT: 'Mit Attest entschuldigt'
} as const

export type AttendanceStatus = keyof typeof STATUS_LABELS

export const ATTENDANCE_STATUSES = Object.keys(
  STATUS_LABELS
) as AttendanceStatus[]

export interface DailyStudent {
  studentId: number
  firstName: string
  lastName: string
  status: AttendanceStatus
  unexcusedMinutes: number
}

export interface CurrentClass {
  classId: number | null
  date: string
}

const CORRECTION_ERRORS: Record<string, string> = {
  ATTENDANCE_DAY_LOCKED_OR_UNPLANNED:
    'Für diesen Tag ist keine Änderung möglich: kein Unterricht geplant oder Tag bereits abgeschlossen.',
  INVALID_STATUS_TIME:
    'Die Uhrzeit muss am heutigen Tag und nicht in der Zukunft liegen.'
}

// Liefert eine verständliche Meldung zu einer abgelehnten Statusänderung
export async function correctionError(response: Response): Promise<string> {
  const body = await response.json().catch(() => ({}))
  return (
    CORRECTION_ERRORS[body.code] ??
    'Statusänderung fehlgeschlagen. Bitte Eingaben und Berechtigung prüfen.'
  )
}
