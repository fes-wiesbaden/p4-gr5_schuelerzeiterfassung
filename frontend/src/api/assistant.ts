import type { CommandHint } from './assistantCommands'
import { apiErrorMessage, loadData, postJson, readProblem } from './client'

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
const notFound = {
  NOT_FOUND:
    'Die ausgewählte Klasse oder Schüler/in ist nicht mehr verfügbar. Wähle neu aus.'
}

export class AssistantRequestError extends Error {
  constructor(
    message: string,
    public readonly status: number
  ) {
    super(message)
  }
}

// Standardtext, falls das Modell keinen Antworttext liefert
export const emptyAnswerText =
  'Darauf habe ich gerade keine Antwort. Formuliere die Frage etwas anders oder wähle unten einen Auftrag aus.'
export const emptyDraftText =
  'Ich habe ein Formular vorbereitet. Prüfe die Angaben, bevor du etwas bestätigst.'

// Ein Abbruch über `signal` beendet die Anfrage sofort; die Aufrufer erkennen
// ihn an `signal.aborted`
async function submit<T>(
  suffix: string,
  data: unknown,
  fallback: string,
  signal?: AbortSignal
): Promise<T> {
  const response = await postJson(assistantPath + suffix, data, signal).catch(
    (failure) => {
      throw signal?.aborted ? failure : new Error(fallback)
    }
  )
  if (!response.ok) {
    const { code } = await readProblem(response)
    throw new AssistantRequestError(
      apiErrorMessage(code, fallback, notFound),
      response.status
    )
  }
  return response.json()
}

export function assistantAvailability() {
  return loadData<{ demoEnabled: boolean }>(assistantPath)
}

export function sendChat(
  messages: ChatMessage[],
  page?: string,
  signal?: AbortSignal
) {
  return submit<AssistantReply>(
    '/messages',
    // Leere Einträge lehnt das Backend ab, deshalb gehen sie nie in die Anfrage
    { messages: messages.filter(({ content }) => content.trim()), page },
    'Die Frage konnte nicht gesendet werden.',
    signal
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
