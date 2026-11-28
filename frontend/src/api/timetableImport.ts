import { ref, watch } from 'vue'

import {
  API_ERRORS,
  apiErrorMessage,
  postForm,
  postJson,
  readProblem
} from './client'
import type { Weekday } from './planning'

export interface DraftSlot {
  weekday: Weekday
  startsAt: string
  endsAt: string
  subject: string | null
  // Vom Bild gelesene Angaben; die Kennungen fehlen, wenn nichts Passendes
  // in den Stammdaten gefunden wurde
  teacherName: string | null
  roomNumber: string | null
  teacherId: number | null
  roomId: number | null
}

// Wem der neue Stundenplan zugeordnet wird. Die Antworten aus dem Chat
// belegen diese Auswahl vor; im Entwurf bleibt sie änderbar
export type ClassTarget =
  | { mode: 'none' }
  | {
      mode: 'existing'
      classId: number
      validFrom?: string
      // Leer bedeutet: gilt bis auf Weiteres
      validUntil?: string
    }
  | {
      mode: 'new'
      classCode: string
      classTeacherId?: number | null
      blockPlanId?: number | null
      validFrom?: string
      validUntil?: string
    }

export interface TimetableDraft {
  name: string
  slots: DraftSlot[]
  notes: string[]
  rooms: { id: number; roomNumber: string }[]
  teachers: { id: number; name: string }[]
  // Vom Bild gelesenes Klassenkürzel und die dazu passende vorhandene Klasse
  classCode: string | null
  classId: number | null
  classes: {
    id: number
    classCode: string
    blocks?: { startsOn: string; endsOn: string }[]
  }[]
  // Montag der im Bild genannten Woche, falls erkennbar
  weekStartsOn?: string | null
  blockPlans: { id: number; name: string; startsOn: string; endsOn: string }[]
  target?: ClassTarget
}

export const MAX_IMAGE_BYTES = 8 * 1024 * 1024
export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

const storageKey = 'stundenplan-entwurf'

function restore(): TimetableDraft | undefined {
  try {
    const stored = sessionStorage.getItem(storageKey)
    return stored ? (JSON.parse(stored) as TimetableDraft) : undefined
  } catch {
    return undefined
  }
}

// Der Entwurf lebt nur im Browser, bis die Übernahme bestätigt wird. Er
// übersteht ein Neuladen der Seite, aber nicht das Schließen des Tabs
export const timetableDraft = ref<TimetableDraft | undefined>(restore())

watch(
  timetableDraft,
  (draft) => {
    try {
      if (draft) sessionStorage.setItem(storageKey, JSON.stringify(draft))
      else sessionStorage.removeItem(storageKey)
    } catch {
      // Ohne Sitzungsspeicher bleibt der Entwurf bis zum Neuladen erhalten
    }
  },
  { deep: true }
)

// Prüft Bildtyp und Größe schon im Browser, damit nichts Unnötiges hochlädt
export function imageProblem(file: File): string {
  if (!IMAGE_TYPES.includes(file.type))
    return API_ERRORS.INVALID_TIMETABLE_IMAGE!
  if (file.size > MAX_IMAGE_BYTES) return API_ERRORS.TIMETABLE_IMAGE_TOO_LARGE!
  return ''
}

// Lässt den Stundenplan vom Bild ablesen. Gespeichert wird dabei nichts
export async function readTimetableImage(
  file: File,
  signal?: AbortSignal
): Promise<TimetableDraft> {
  const form = new FormData()
  form.append('image', file)
  let response: Response
  try {
    response = await postForm('/api/assistant/timetable-drafts', form, signal)
  } catch (failure) {
    if (signal?.aborted) throw failure
    throw new Error('Das Bild konnte nicht hochgeladen werden.')
  }
  if (!response.ok) {
    const { code } = await readProblem(response)
    throw new Error(
      apiErrorMessage(
        code ?? (response.status === 413 ? 'TIMETABLE_IMAGE_TOO_LARGE' : ''),
        'Der Stundenplan konnte nicht gelesen werden. Versuch es erneut.'
      )
    )
  }
  const draft = (await response.json()) as TimetableDraft
  return {
    ...draft,
    classCode: draft.classCode ?? null,
    classId: draft.classId ?? null,
    classes: draft.classes ?? [],
    blockPlans: draft.blockPlans ?? []
  }
}

export interface ImportSlot {
  weekday: Weekday
  startsAt: string
  endsAt: string
  roomId: number
  subject: string | null
  teacherId: number | null
}

export class TimetableImportError extends Error {
  constructor(
    message: string,
    public readonly slotIndex: number | null
  ) {
    super(message)
  }
}

// Wortlaut, der beim Import genauer ist als der allgemeine Hinweis
const importErrors: Record<string, string> = {
  INVALID_TIMETABLE_SLOT:
    'Eine Unterrichtszeit ist unvollständig: Der Beginn muss vor dem Ende liegen und ein Raum muss gewählt sein.',
  OVERLAPPING_TIMETABLE_SLOT:
    'Zwei Unterrichtszeiten überschneiden sich am selben Wochentag.',
  CLASS_CODE_ALREADY_EXISTS:
    'Eine Klasse mit diesem Kürzel gibt es bereits. Wähle sie als bestehende Klasse aus.',
  NOT_FOUND: 'Die gewählte Klasse gibt es nicht mehr.',
  ROOM_ALREADY_ASSIGNED:
    'Ein Raum des neuen Plans ist in einem Unterrichtsblock der Klasse bereits durch eine andere Klasse belegt.',
  TIMETABLE_REUSE_NOT_ALLOWED:
    'Der Stundenplan kollidiert mit der Planung einer anderen Klasse.'
}

// Legt den geprüften Entwurf als neuen Stundenplan an – ganz oder gar nicht
export type ImportClass =
  | { id: number; validFrom: string; validUntil: string | null }
  | {
      classCode: string
      classTeacherId: number
      blockPlanId: number
      validFrom: string | null
      validUntil: string | null
    }
  | null

export async function importTimetable(
  name: string,
  slots: ImportSlot[],
  schoolClass: ImportClass = null
): Promise<number> {
  const response = await postJson('/api/planning/timetables/import', {
    name,
    slots,
    schoolClass
  })
  if (!response.ok) {
    const problem = await readProblem(response)
    throw new TimetableImportError(
      apiErrorMessage(
        problem.code,
        response.status === 403
          ? 'Dafür fehlt dir die Berechtigung.'
          : 'Der Stundenplan konnte nicht angelegt werden. Versuch es erneut.',
        importErrors
      ),
      problem.slotIndex ?? null
    )
  }
  return ((await response.json()) as { id: number }).id
}
