import {
  apiErrorMessage,
  deleteResource,
  getJson,
  loadData,
  postJson,
  putJson,
  readProblem
} from '@/api/client'

export type Weekday =
  'MONTAG' | 'DIENSTAG' | 'MITTWOCH' | 'DONNERSTAG' | 'FREITAG'

export const WEEKDAYS: { value: Weekday; label: string }[] = [
  { value: 'MONTAG', label: 'Montag' },
  { value: 'DIENSTAG', label: 'Dienstag' },
  { value: 'MITTWOCH', label: 'Mittwoch' },
  { value: 'DONNERSTAG', label: 'Donnerstag' },
  { value: 'FREITAG', label: 'Freitag' }
]

interface SlotInput {
  weekday: Weekday
  startsAt: string
  endsAt: string
  roomId: number
  subject?: string | null
  teacherId?: number | null
}

export interface Slot extends SlotInput, ScheduledLesson {
  id: number
  validFrom: string | null
}

export interface Timetable {
  id: number
  name: string
  slots: Slot[]
  periods?: TimetablePeriod[]
}

export interface TimetablePeriod {
  number: number
  startsAt: string
  endsAt: string
}

export interface Block {
  id: number
  startsOn: string
  endsOn: string
  classIds: number[]
}

export interface BlockPlan {
  id: number
  name: string
  startsOn: string
  endsOn: string
  deleteAfter: string
  blocks: Block[]
}

export interface Room {
  id: number
  roomNumber: string
}

export interface PlanningClass {
  id: number
  classCode: string
  blockPlanId: number
  timetableId: number
}

export interface PlanningOverview {
  timetables: Timetable[]
  blockPlans: BlockPlan[]
  rooms: Room[]
  classes: PlanningClass[]
  teachers?: { id: number; name: string }[]
}

// Hinweistext zu einem Fehlercode aus der gemeinsamen Tabelle
export function errorMessage(code: string | undefined): string {
  return apiErrorMessage(
    code,
    'Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.',
    { NOT_FOUND: 'Der Datensatz wurde nicht gefunden.' }
  )
}

// Wirft bei einer fehlgeschlagenen Antwort einen Fehler mit deutschem Hinweistext
export async function expectOk(response: Response): Promise<Response> {
  if (response.ok) return response
  throw new Error(errorMessage((await readProblem(response)).code))
}

export async function loadOverview(
  signal?: AbortSignal
): Promise<PlanningOverview> {
  const response = await expectOk(await getJson('/api/planning', signal))
  return (await response.json()) as PlanningOverview
}

async function createdId(response: Response): Promise<number> {
  return ((await response.json()) as { id: number }).id
}

export async function createTimetable(name: string): Promise<number> {
  return createdId(
    await expectOk(await postJson('/api/planning/timetables', { name }))
  )
}

export async function addSlot(
  timetableId: number,
  slot: SlotInput
): Promise<void> {
  await expectOk(
    await postJson(`/api/planning/timetables/${timetableId}/slots`, slot)
  )
}

export async function updateSlot(
  timetableId: number,
  slotId: number,
  slot: SlotInput
): Promise<string> {
  const response = await expectOk(
    await putJson(
      `/api/planning/timetables/${timetableId}/slots/${slotId}`,
      slot
    )
  )
  return ((await response.json()) as { effectiveOn: string }).effectiveOn
}

export async function deleteSlot(
  timetableId: number,
  slotId: number
): Promise<string> {
  const response = await expectOk(
    await deleteResource(
      `/api/planning/timetables/${timetableId}/slots/${slotId}`
    )
  )
  return ((await response.json()) as { effectiveOn: string }).effectiveOn
}

export async function createBlockPlan(plan: {
  name: string
  startsOn: string
  endsOn: string
}): Promise<number> {
  return createdId(
    await expectOk(await postJson('/api/planning/block-plans', plan))
  )
}

export async function addBlock(
  blockPlanId: number,
  block: { startsOn: string; endsOn: string }
): Promise<void> {
  await expectOk(
    await postJson(`/api/planning/block-plans/${blockPlanId}/blocks`, block)
  )
}

export async function assignBlock(
  classId: number,
  blockId: number
): Promise<void> {
  await expectOk(
    await postJson(`/api/planning/classes/${classId}/blocks`, { blockId })
  )
}

export async function changeBlockPlan(
  classId: number,
  expectedBlockPlanId: number,
  nextBlockPlanId: number
): Promise<void> {
  await expectOk(
    await postJson(`/api/planning/classes/${classId}/block-plan-change`, {
      expectedBlockPlanId,
      nextBlockPlanId
    })
  )
}

// Liefert die Anzahl der Schüler, deren Konten zurückgesetzt wurden
export async function resetAccounts(
  classId: number,
  expectedBlockPlanId: number
): Promise<number> {
  const response = await expectOk(
    await postJson(`/api/classes/${classId}/account-resets`, {
      expectedBlockPlanId
    })
  )
  return ((await response.json()) as { resetCount: number }).resetCount
}

export function formatTime(time: string): string {
  return time.length === 8 && time.endsWith(':00') ? time.slice(0, 5) : time
}

export interface ScheduledLesson {
  startsAt: string
  endsAt: string
  roomNumber: string
  subject?: string | null
  teacherName?: string | null
  // Für dieses Datum als Ausfall eingetragen
  cancelled?: boolean
}

export interface ClassWeekDay {
  date: string
  lessons: ScheduledLesson[]
  periods?: TimetablePeriod[]
}

export interface TeachingBlock {
  startsOn: string
  endsOn: string
}

export interface ClassSchedule {
  schoolYear: number
  blocks: TeachingBlock[]
}

export function loadClassSchedule(classId: number, signal?: AbortSignal) {
  return loadData<ClassSchedule>(
    `/api/planning/classes/${classId}/schedule`,
    signal
  )
}

// Trägt Unterrichtsausfall für ein Datum ein. Ohne Uhrzeit gilt er für alle
// Unterrichtszeiten des Tages
export interface ManualAbsenceHint {
  studentId: number
  firstName: string
  lastName: string
  date: string
  minutes: number
}

export async function cancelLessons(
  classId: number,
  date: string,
  startsAt?: string
): Promise<ManualAbsenceHint[]> {
  const response = await expectOk(
    await postJson(`/api/planning/classes/${classId}/lesson-cancellations`, {
      date,
      startsAt: startsAt ? formatTime(startsAt) : null
    })
  )
  // Schüler mit händischen Fehlminuten an diesem Tag, damit die Lehrkraft
  // prüfen kann, ob sie zur ausgefallenen Zeit gehören
  return (
    ((await response.json()) as { manualAbsences?: ManualAbsenceHint[] })
      .manualAbsences ?? []
  )
}

// Nimmt eingetragenen Unterrichtsausfall wieder zurück
export async function restoreLessons(
  classId: number,
  date: string,
  startsAt?: string
): Promise<boolean> {
  const query = new URLSearchParams({ date })
  if (startsAt) query.set('startsAt', formatTime(startsAt))
  const response = await expectOk(
    await deleteResource(
      `/api/planning/classes/${classId}/lesson-cancellations?${query}`
    )
  )
  return ((await response.json()) as { changed: boolean }).changed
}

export function loadClassWeek(
  classId: number,
  date: string,
  signal?: AbortSignal
) {
  return loadData<ClassWeekDay[]>(
    `/api/planning/classes/${classId}/week?date=${encodeURIComponent(date)}`,
    signal
  )
}
