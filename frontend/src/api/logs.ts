import { loadData } from './client'
import { BERLIN } from './attendance'

export interface RawScanEntry {
  scanId: string
  scannedAt: string
  rfidUid: string
  student: string | null
  classCode: string | null
}

export interface AuditEntry {
  id: number
  occurredAt: string
  eventType: AuditEventType
  student: string
  classCode: string
  staffName: string | null
  staffUsername: string | null
  terminalId: number | null
  roomNumber: string | null
  oldStatus: string | null
  newStatus: string | null
  unexcusedMinutesDelta: number
  excusedMinutesDelta: number
}

export interface LogPage<T> {
  items: T[]
  total: number
}

// Ereignistypen wie im Backend-Enum AuditEventType
export const EVENT_LABELS = {
  SCAN: 'Scan',
  STATUS_CHANGE: 'Statuskorrektur',
  DAILY_CLOSE: 'Tagesabschluss',
  ACCOUNT_RESET: 'Kontenreset'
} as const

export type AuditEventType = keyof typeof EVENT_LABELS

export function loadLogPage<T>(
  kind: 'raw-scans' | 'audits',
  params: Record<string, string | number | undefined>
): Promise<LogPage<T>> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') query.set(key, String(value))
  }
  return loadData(`/api/logs/${kind}?${query}`)
}

// Beginn des Berliner Kalendertags YYYY-MM-DD als UTC-Zeitpunkt. Um Mitternacht
// gilt immer derselbe Versatz wie am restlichen Tag, die Umstellung ist erst um 2 Uhr
export function berlinDayStart(day: string): string {
  const utc = new Date(`${day}T00:00:00Z`)
  const berlinWallClock = new Date(
    utc.toLocaleString('en-US', { timeZone: BERLIN })
  )
  const utcWallClock = new Date(
    utc.toLocaleString('en-US', { timeZone: 'UTC' })
  )
  return new Date(
    utc.getTime() - (berlinWallClock.getTime() - utcWallClock.getTime())
  ).toISOString()
}

export function nextDay(day: string): string {
  const date = new Date(`${day}T12:00:00Z`)
  date.setUTCDate(date.getUTCDate() + 1)
  return date.toISOString().slice(0, 10)
}

export function formatDateTime(at: string): string {
  return new Intl.DateTimeFormat('de-DE', {
    timeZone: BERLIN,
    dateStyle: 'medium',
    timeStyle: 'medium'
  }).format(new Date(at))
}
