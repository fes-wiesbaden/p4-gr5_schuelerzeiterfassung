import { deleteResource, getJson, postJson, putJson } from '@/api/client'

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
}

export interface Slot extends SlotInput {
  id: number
  roomNumber: string
  validFrom: string | null
}

export interface Timetable {
  id: number
  name: string
  slots: Slot[]
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
}

// Übersetzt die festen Fehlercodes des Backends in verständliche Hinweise
const ERROR_MESSAGES: Record<string, string> = {
  INVALID_REQUEST: 'Die Eingaben sind unvollständig oder ungültig.',
  INVALID_TIMETABLE: 'Bitte einen Namen mit höchstens 150 Zeichen angeben.',
  INVALID_TIMETABLE_SLOT:
    'Der Beginn muss vor dem Ende liegen und ein Raum muss gewählt sein.',
  INVALID_BLOCK_PLAN:
    'Bitte Namen angeben; der Beginn darf nicht nach dem Ende liegen.',
  INVALID_BLOCK_ASSIGNMENT:
    'Der Block muss innerhalb des Blockplans liegen; der Beginn darf nicht nach dem Ende liegen.',
  OVERLAPPING_TIMETABLE_SLOT:
    'Der Slot überschneidet sich mit einem vorhandenen Slot dieses Stundenplans.',
  OVERLAPPING_BLOCK_ASSIGNMENT:
    'Der Block überschneidet sich mit einem vorhandenen Block dieses Blockplans.',
  BLOCK_ASSIGNMENT_BELONGS_TO_ANOTHER_PLAN:
    'Der Block gehört nicht zum aktuellen Blockplan der Klasse.',
  BLOCK_ALREADY_ASSIGNED: 'Die Klasse ist diesem Block bereits zugeordnet.',
  ROOM_ALREADY_ASSIGNED:
    'Ein Raum des Stundenplans ist in diesem Zeitraum bereits durch eine andere Klasse belegt.',
  TIMETABLE_REUSE_NOT_ALLOWED:
    'Eine andere Klasse nutzt denselben Stundenplan in einem überschneidenden Block.',
  CLASS_PLAN_CHANGED:
    'Der Blockplan der Klasse wurde inzwischen geändert. Bitte die Seite neu laden.',
  CURRENT_PLAN_NOT_FINISHED:
    'Der aktuelle Blockplan oder einer seiner Blöcke ist noch nicht beendet.',
  NEXT_PLAN_OVERLAPS_CURRENT_PLAN:
    'Der Folgeplan muss nach dem Ende des aktuellen Blockplans beginnen.',
  NEXT_PLAN_FINISHED: 'Der gewählte Folgeplan ist bereits beendet.',
  CLASS_HAS_NO_STUDENTS: 'Die Klasse hat keine Schüler.',
  CLASS_HAS_REMAINING_SLOTS: 'Heute finden für die Klasse noch Slots statt.',
  CLASS_NOT_FULLY_ACCOUNTED:
    'Es sind noch nicht alle Tage der Klasse abgerechnet.',
  CONCURRENT_PLANNING_CHANGE:
    'Die Planung wurde gleichzeitig geändert. Bitte die Seite neu laden und erneut versuchen.',
  ADMINISTRATOR_REQUIRED: 'Diese Aktion ist Administratoren vorbehalten.',
  CLASS_MANAGEMENT_DENIED: 'Nur die Klassenleitung darf den Plan wechseln.',
  NOT_FOUND: 'Der Datensatz wurde nicht gefunden.',
  ATTENDANCE_DAY_LOCKED_OR_UNPLANNED:
    'Für diesen Tag ist keine Änderung möglich: kein Unterricht geplant oder Tag bereits abgeschlossen.',
  INVALID_STATUS_TIME:
    'Die Uhrzeit muss am heutigen Tag und nicht in der Zukunft liegen.'
}

export function errorMessage(code: string | undefined): string {
  return (
    (code && ERROR_MESSAGES[code]) ||
    'Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.'
  )
}

// Wirft bei einer fehlgeschlagenen Antwort einen Fehler mit deutschem Hinweistext
export async function expectOk(response: Response): Promise<Response> {
  if (response.ok) {
    return response
  }
  let code: string | undefined
  try {
    code = ((await response.json()) as { code?: string }).code
  } catch {
    code = undefined
  }
  throw new Error(errorMessage(code))
}

export async function loadOverview(): Promise<PlanningOverview> {
  const response = await expectOk(await getJson('/api/planning'))
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

// Zeigt ISO-Datumsangaben im deutschen Format an
export function formatDate(iso: string): string {
  const [year, month, day] = iso.split('-')
  return `${day}.${month}.${year}`
}

export function formatTime(time: string): string {
  return time.slice(0, 5)
}
