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

export interface DailyStudent {
  studentId: number
  firstName: string
  lastName: string
  status: AttendanceStatus
  unexcusedMinutes: number
}

export interface CurrentClass {
  classId: number | null
  serverTime: string
}

export const BERLIN = 'Europe/Berlin'

// Liefert den Berliner Kalendertag als YYYY-MM-DD
export function berlinDate(at: Date): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: BERLIN }).format(at)
}

// Liefert die Berliner Uhrzeit als HH:MM
export function berlinTime(at: Date): string {
  return new Intl.DateTimeFormat('de-DE', {
    timeZone: BERLIN,
    hour: '2-digit',
    minute: '2-digit'
  }).format(at)
}

// Wandelt Berliner Datum und Uhrzeit unabhängig von der Zeitzone des Browsers
// in einen sekundengenauen UTC-Zeitpunkt um
// shortcut: Offset wird zur als UTC gelesenen Uhrzeit bestimmt und kann in der Stunde der nächtlichen Zeitumstellung um eine Stunde abweichen; genügt, solange dann kein Unterricht stattfindet
export function berlinInstant(date: string, time: string): string {
  const local = `${date}T${time}:00`
  const offset = new Intl.DateTimeFormat('en-US', {
    timeZone: BERLIN,
    timeZoneName: 'longOffset'
  })
    .formatToParts(new Date(`${local}Z`))
    .find((part) => part.type === 'timeZoneName')!
    .value.replace('GMT', '')
  return new Date(`${local}${offset}`).toISOString()
}
