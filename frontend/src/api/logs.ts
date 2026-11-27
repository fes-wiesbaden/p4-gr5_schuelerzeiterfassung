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
  // Lehrkraft mit Benutzername, Terminal mit Raum oder System
  actor: string
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
  params: Record<string, string | number>
): Promise<LogPage<T>> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== '') query.set(key, String(value))
  }
  return loadData(`/api/logs/${kind}?${query}`)
}

export function formatDateTime(at: string): string {
  return new Intl.DateTimeFormat('de-DE', {
    timeZone: BERLIN,
    dateStyle: 'medium',
    timeStyle: 'medium'
  }).format(new Date(at))
}
