import type { CommandHint } from './assistantCommands'
import { loadData, postJson } from './client'

export type ChatMessage = { role: 'user' | 'assistant'; content: string }

export type ExcusalScope = 'time' | 'day' | 'range'
export type ExcusalStatus =
  'ENTSCHULDIGT' | 'BETRIEBLICH_ENTSCHULDIGT' | 'MIT_ATTEST_ENTSCHULDIGT'

export type ExcusalHint = {
  studentName?: string | null
  classCode?: string | null
  date?: string | null
  startsAt?: string | null
  endsAt?: string | null
  endDate?: string | null
  scope?: ExcusalScope | null
  status?: ExcusalStatus | null
  reason?: string | null
}

export type AssistantReply = ExcusalHint & {
  kind: 'answer' | 'navigate' | 'excusal' | 'command'
  command?: CommandHint | null
  answer: string
  route: string | null
}

export type ExcusalDay = {
  date: string
  pending: boolean
  before: 'ABWESEND' | 'ENTSCHULDIGT' | null
  after: 'ABWESEND' | 'ENTSCHULDIGT' | null
  unexcusedRemoved: number
  excusedAdded: number
}

export type ExcusalImpact = {
  unexcusedRemoved: number
  excusedAdded: number
  days: ExcusalDay[]
}
export type ExcusalPreview = { id: string | null; impact: ExcusalImpact }
export type Excusal = {
  classId: number
  studentId: number
  date: string
  startsAt?: string
  endsAt?: string
  endDate?: string
  status: ExcusalStatus
  reason?: string
}

const assistantPath = '/api/assistant'
const messages: Record<string, string> = {
  ASSISTANT_BUSY:
    'Der Assistent beantwortet gerade eine Frage. Versuch es in einem Moment erneut.',
  ASSISTANT_UNAVAILABLE: 'Klassenlotse ist gerade nicht erreichbar.',
  ATTENDANCE_DAY_LOCKED_OR_UNPLANNED:
    'Für dieses Datum ist keine Buchung möglich. Prüfe die Unterrichtsplanung und das Datum.',
  INVALID_EXCUSAL_TIME:
    'Prüfe Datum und Uhrzeit. Für mehrere Tage ist ein Attest erforderlich.',
  EXCUSAL_PREVIEW_CHANGED:
    'Die Fehlzeit hat sich geändert. Berechne die Vorschau erneut.',
  EXCUSAL_PREVIEW_EXPIRED:
    'Diese Vorschau ist abgelaufen. Berechne sie erneut.',
  NOT_FOUND: 'Die ausgewählte Person oder Klasse ist nicht mehr verfügbar.'
}

export class AssistantRequestError extends Error {
  constructor(
    message: string,
    public readonly status: number
  ) {
    super(message)
  }
}

async function submit<T>(
  suffix: string,
  data: unknown,
  fallback: string
): Promise<T> {
  const response = await postJson(assistantPath + suffix, data).catch(() => {
    throw new Error(fallback)
  })
  if (!response.ok) {
    const detail = (await response.json().catch(() => null)) as {
      code?: string
    } | null
    throw new AssistantRequestError(
      detail?.code ? (messages[detail.code] ?? fallback) : fallback,
      response.status
    )
  }
  return response.json()
}

export function assistantAvailability() {
  return loadData<{ demoEnabled: boolean }>(assistantPath)
}

export function sendChat(messages: ChatMessage[], page?: string) {
  return submit<AssistantReply>(
    '/messages',
    { messages, page },
    'Die Frage konnte nicht gesendet werden.'
  )
}

export function previewExcusal(excusal: Excusal) {
  return submit<ExcusalPreview>(
    '/excusals/preview',
    excusal,
    'Die Vorschau konnte nicht berechnet werden.'
  )
}

export function confirmExcusal(id: string) {
  return submit<ExcusalImpact>(
    `/excusals/${encodeURIComponent(id)}/confirm`,
    {},
    'Die Buchung konnte nicht bestätigt werden.'
  )
}
