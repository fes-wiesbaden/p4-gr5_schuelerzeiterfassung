import { loadData } from './client'

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
  wasPresent: boolean
  fullAbsence: 'ABWESEND' | 'ENTSCHULDIGT' | null
  manualAbsenceMinutes: number
  birthDate?: string
  // ABWESEND ist trotz erfasster Anwesenheit zulässig, wenn kein Scan vorliegt
  canMarkAbsent?: boolean
}

export interface CurrentClass {
  classId: number | null
  serverTime: string
}

// Kurze Erklärung je Status für Auswahllisten und Hilfetexte
export const STATUS_HINTS: Record<AttendanceStatus, string> = {
  ANWESEND: 'Nimmt am Unterricht teil.',
  ABWESEND: 'Fehlt unentschuldigt.',
  BETRIEB: 'Ist im Ausbildungsbetrieb – zählt nicht als Fehlzeit.',
  BETRIEBLICH_ENTSCHULDIGT:
    'Fehlt aus betrieblichen Gründen – zählt als entschuldigt.',
  ENTSCHULDIGT: 'Fehlt mit Entschuldigung.',
  MIT_ATTEST_ENTSCHULDIGT: 'Fehlt mit ärztlichem Attest.'
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
    minute: '2-digit',
    hourCycle: 'h23'
  }).format(at)
}

// Wandelt Berliner Datum und Uhrzeit unabhängig von der Zeitzone des Browsers
// in einen sekundengenauen UTC-Zeitpunkt um
// shortcut: Offset wird zur als UTC gelesenen Uhrzeit bestimmt und kann in der Stunde der nächtlichen Zeitumstellung um eine Stunde abweichen; genügt, solange dann kein Unterricht stattfindet
export function berlinInstant(date: string, time: string): string {
  const local = `${date}T${time.length === 5 ? time + ':00' : time}`
  const offset = new Intl.DateTimeFormat('en-US', {
    timeZone: BERLIN,
    timeZoneName: 'longOffset'
  })
    .formatToParts(new Date(`${local}Z`))
    .find((part) => part.type === 'timeZoneName')!
    .value.replace('GMT', '')
  return new Date(`${local}${offset}`).toISOString()
}

export type SchoolClass = { id: number; classCode: string }

export function todayInBerlin(): string {
  return berlinDate(new Date())
}

export function loadClasses(signal?: AbortSignal) {
  return loadData<SchoolClass[]>('/api/classes', signal)
}
