import type { RouteLocationRaw } from 'vue-router'
import { berlinInstant, STATUS_LABELS, type DailyStudent } from './attendance'
import { deleteResource, loadData, postJson, putJson } from './client'
import {
  addBlock,
  addSlot,
  assignBlock,
  changeBlockPlan,
  createBlockPlan,
  createTimetable,
  deleteSlot,
  errorMessage,
  formatTime,
  loadClassWeek,
  loadOverview,
  resetAccounts,
  updateSlot,
  WEEKDAYS,
  type PlanningOverview,
  type ScheduledLesson,
  type Slot,
  type Weekday
} from './planning'
import {
  formatAbsenceTotals,
  studentLabel,
  type Student,
  type StudentClass
} from './students'
import { formatDate } from '@/dateTime'
import { loadAttendanceWeek } from './attendanceHistory'

export type CommandHint = { key: string; fields: Record<string, string | null> }
export type CommandResult = {
  text: string
  link?: { label: string; to: RouteLocationRaw }
}
export type Values = Record<string, string>
type ClassRecord = StudentClass & { blockPlanId: number; timetableId: number }
type NamedOption = { id: number; name: string }
type Teacher = {
  id: number
  firstName: string
  lastName: string
  username: string
}
type Room = { id: number; roomNumber: string }

export type Field = {
  key: string
  label: string
  kind: 'text' | 'date' | 'time' | 'number' | 'secret' | 'choice'
  options?: (context: CommandContext, values: Values) => Choice[]
  length?: number
  optional?: boolean
}
export type Choice = { value: string; label: string; aliases?: string[] }
export type CommandContext = {
  classes: ClassRecord[]
  students: Student[]
  staffId: number | null
  plans: NamedOption[]
  timetables: NamedOption[]
  planning?: PlanningOverview
  rooms: Room[]
  teachers: Teacher[]
  admin: boolean
}
export type Definition = {
  title: string
  fields: Field[]
  needs: (
    | 'classes'
    | 'students'
    | 'staff'
    | 'options'
    | 'planning'
    | 'rooms'
    | 'teachers'
  )[]
  admin?: boolean
  danger?: boolean
  read?: boolean
  note?: string
  run: (values: Values, context: CommandContext) => Promise<CommandResult>
}

const text = (key: string, label: string, length = 100): Field => ({
  key,
  label,
  kind: 'text',
  length
})
const date = (key: string, label: string): Field => ({
  key,
  label,
  kind: 'date'
})
const time = (key: string, label: string): Field => ({
  key,
  label,
  kind: 'time'
})
const choice = (
  key: string,
  label: string,
  options: NonNullable<Field['options']>
): Field => ({ key, label, kind: 'choice', options })

const cls = (managed = false) =>
  choice('classCode', 'Klasse', (ctx) =>
    ctx.classes
      .filter((c) => !managed || ctx.admin || c.classTeacherId === ctx.staffId)
      .map((c) => ({ value: String(c.id), label: c.classCode }))
  )
const student = choice('studentName', 'Schüler oder Schülerin', (ctx, values) =>
  ctx.students
    .filter((item) => item.classId === Number(values.classCode))
    .map((item) => ({
      value: String(item.id),
      label: `${studentLabel(item)} · ${formatDate(item.birthDate)}`,
      aliases: [studentLabel(item), `${item.firstName} ${item.lastName}`]
    }))
)
const names = [text('firstName', 'Vorname'), text('lastName', 'Nachname')]
const studentDetails = [...names, date('birthDate', 'Geburtsdatum')]
const attendanceDate = date('date', 'Datum')
const period = [date('date', 'Beginn'), date('endDate', 'Ende')]
const startsAt = time('startsAt', 'Beginn')
const endsAt = time('endsAt', 'Ende')
const status = choice('status', 'Neuer Status', () =>
  Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))
)
const weekday = choice('weekday', 'Wochentag', () =>
  WEEKDAYS.map((day) => ({ value: day.value, label: day.label }))
)
const timetable = choice('timetable', 'Stundenplan', (ctx) =>
  ctx.timetables.map((item) => ({ value: String(item.id), label: item.name }))
)
const classCode = text('name', 'Klassenkürzel', 30)
const currentSlot = choice('slot', 'Bisherige Unterrichtszeit', (ctx, values) =>
  (
    ctx.planning?.timetables.find(
      (item) => item.id === Number(values.timetable)
    )?.slots ?? []
  ).map((item) => {
    const day = WEEKDAYS.find(
      (weekday) => weekday.value === item.weekday
    )?.label
    return {
      value: String(item.id),
      label: `${day} ${formatTime(item.startsAt)}–${formatTime(item.endsAt)} · Raum ${item.roomNumber}`,
      aliases: [
        `${day} ${formatTime(item.startsAt)}`,
        `${item.weekday} ${formatTime(item.startsAt)}`
      ]
    }
  })
)
const room = (key = 'room', label = 'Raum') =>
  choice(key, label, (ctx) =>
    ctx.rooms.map((item) => ({
      value: String(item.id),
      label: item.roomNumber,
      aliases: [`Raum ${item.roomNumber}`]
    }))
  )
const plan = choice('blockPlan', 'Blockplan', (ctx) =>
  ctx.plans.map((item) => ({ value: String(item.id), label: item.name }))
)
const nextPlan = { ...plan, label: 'Folgeplan' }
const block = choice('block', 'Unterrichtsblock', (ctx, values) => {
  const classPlan = ctx.classes.find(
    (item) => item.id === Number(values.classCode)
  )?.blockPlanId
  return (
    ctx.planning?.blockPlans.find((item) => item.id === classPlan)?.blocks ?? []
  )
    .filter((item) => !item.classIds.includes(Number(values.classCode)))
    .map((item) => ({
      value: String(item.id),
      label: `${formatDate(item.startsOn)} – ${formatDate(item.endsOn)}`,
      aliases: [item.startsOn, formatDate(item.startsOn)]
    }))
})
const teacher = (label = 'Klassenleitung') =>
  choice('teacher', label, (ctx) =>
    ctx.teachers.map((item) => ({
      value: String(item.id),
      label: `${item.firstName} ${item.lastName} · ${item.username}`,
      aliases: [`${item.firstName} ${item.lastName}`, item.username]
    }))
  )
const slotFields = [weekday, startsAt, endsAt, room()]
const result = (text: string): CommandResult => ({ text })
const currentClass = (values: Values, ctx: CommandContext) =>
  ctx.classes.find((c) => c.id === Number(values.classCode))!
const currentStudent = (values: Values, ctx: CommandContext) =>
  ctx.students.find((s) => s.id === Number(values.studentName))!
const studentPath = (values: Values) =>
  `/api/students/${Number(values.studentName)}`
const dayPath = (values: Values) =>
  `${studentPath(values)}/attendance/${encodeURIComponent(values.date ?? '')}`
const classPath = (values: Values) => `/api/classes/${Number(values.classCode)}`
const details = (v: Values) => ({
  firstName: v.firstName,
  lastName: v.lastName
})
function slotBody(v: Values) {
  return {
    weekday: v.weekday as Weekday,
    startsAt: v.startsAt!,
    endsAt: v.endsAt!,
    roomId: Number(v.room)
  }
}

function lessonSummary(lesson: ScheduledLesson) {
  return [
    `${formatTime(lesson.startsAt)}–${formatTime(lesson.endsAt)} Uhr`,
    lesson.roomNumber ? `Raum ${lesson.roomNumber}` : null
  ]
    .filter(Boolean)
    .join(' · ')
}

async function write(
  path: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body: unknown,
  success: string
): Promise<CommandResult> {
  const response = await (method === 'DELETE'
    ? deleteResource(path)
    : method === 'POST'
      ? postJson(path, body)
      : putJson(path, body))
  const payload =
    response.status === 204 ? null : await response.json().catch(() => null)
  if (!response.ok) {
    if (response.status === 403)
      throw new Error('Für diese Aktion fehlen dir die Rechte.')
    if (payload?.code === 'STUDENT_HAS_HISTORY') {
      throw new Error(
        'Dieser Schüler hat bereits eine Anwesenheitshistorie. Die App lässt das Löschen deshalb nicht zu.'
      )
    }
    if (response.status === 409 && !payload?.code) {
      throw new Error(
        'Der Eintrag wird noch verwendet oder die Änderung steht im Konflikt mit vorhandenen Daten.'
      )
    }
    throw new Error(errorMessage(payload?.code))
  }
  return result(
    payload?.changed === false
      ? 'Die Angaben waren bereits so gespeichert.'
      : success
  )
}

function attendanceCommand(
  title: string,
  fields: Field[],
  endpoint: string,
  body: (values: Values) => unknown,
  success: string
): Definition {
  return {
    title,
    fields: [cls(), student, attendanceDate, ...fields],
    needs: ['classes', 'students'],
    run: (values) =>
      write(`${dayPath(values)}/${endpoint}`, 'POST', body(values), success)
  }
}

export const commands: Record<string, Definition> = {
  read_student: {
    title: 'Schülerprofil anzeigen',
    fields: [cls(), student],
    needs: ['classes', 'students'],
    read: true,
    run: async (v, ctx) => {
      const s = currentStudent(v, ctx)
      return {
        text: `${s.firstName} ${s.lastName} · ${currentClass(v, ctx).classCode}\nUnentschuldigt: ${formatAbsenceTotals(s.unexcusedDays, s.unexcusedHours)}\nEntschuldigt: ${formatAbsenceTotals(s.excusedDays, s.excusedHours)}`,
        link: {
          label: 'Schülerprofil öffnen',
          to: {
            name: 'schueler-detail',
            params: { studentId: s.id },
            query: { klasse: s.classId }
          }
        }
      }
    }
  },
  read_attendance: {
    title: 'Tagesanwesenheit anzeigen',
    fields: [cls(), attendanceDate],
    needs: ['classes'],
    read: true,
    run: async (v, ctx) => {
      const records = await loadData<DailyStudent[]>(
        `${classPath(v)}/attendance?date=${encodeURIComponent(v.date!)}`
      )
      const absent = records.filter((item) => item.status === 'ABWESEND')
      const first = absent
        .slice(0, 12)
        .map((s) => `${s.firstName} ${s.lastName}`)
        .join(', ')
      const summary = absent.length
        ? `${absent.length} abwesend: ${first}${absent.length > 12 ? ' …' : ''}`
        : 'Niemand ist als abwesend eingetragen.'
      return {
        text: `${currentClass(v, ctx).classCode} · ${formatDate(v.date!)}\n${records.length ? summary : 'Für diesen Tag sind keine Anwesenheitsdaten verfügbar.'}`,
        link: {
          label: 'Tagesanwesenheit öffnen',
          to: {
            name: 'live-anwesenheit',
            query: { klasse: v.classCode, datum: v.date }
          }
        }
      }
    }
  },
  read_timetable: {
    title: 'Unterrichtszeiten anzeigen',
    fields: [cls(), attendanceDate],
    needs: ['classes'],
    read: true,
    run: async (v, ctx) => {
      const week = await loadClassWeek(Number(v.classCode), v.date!)
      const lessons = week.find((day) => day.date === v.date)?.lessons ?? []
      return {
        text: `${currentClass(v, ctx).classCode} · ${formatDate(v.date!)}\n${
          lessons.length
            ? lessons.map(lessonSummary).join('\n')
            : 'An diesem Tag ist kein Unterricht geplant.'
        }`,
        link: {
          label: 'Wochenstundenplan öffnen',
          to: { name: 'planung', query: { klasse: v.classCode, datum: v.date } }
        }
      }
    }
  },
  read_week: {
    title: 'Wochenstundenplan anzeigen',
    fields: [cls(), attendanceDate],
    needs: ['classes'],
    read: true,
    run: async (v, ctx) => {
      const days = await loadClassWeek(Number(v.classCode), v.date!)
      const text = days.map((day, index) => {
        const lessons = day.lessons.map(lessonSummary)
        return `${WEEKDAYS[index]?.label} ${formatDate(day.date)}: ${lessons.join(', ') || 'kein Unterricht'}`
      })
      return {
        text: `${currentClass(v, ctx).classCode}
${text.join('\n')}`,
        link: {
          label: 'Wochenstundenplan öffnen',
          to: { name: 'planung', query: { klasse: v.classCode, datum: v.date } }
        }
      }
    }
  },
  read_history: {
    title: 'Anwesenheitshistorie anzeigen',
    fields: [cls(), student, attendanceDate],
    needs: ['classes', 'students'],
    read: true,
    run: async (v, ctx) => {
      const person = currentStudent(v, ctx)
      const week = await loadAttendanceWeek(person.id, v.date!)
      const entries = week.days
        .filter((day) => day.unexcusedMinutes !== null)
        .map((day) => {
          const detail =
            day.fullAbsence === 'ABWESEND'
              ? '1 unentschuldigter Fehltag'
              : day.fullAbsence === 'ENTSCHULDIGT'
                ? '1 entschuldigter Fehltag'
                : `${day.unexcusedMinutes} Min. unentschuldigt · ${day.excusedMinutes} Min. entschuldigt`
          return `${formatDate(day.date)} · ${detail}`
        })
      return {
        text: `${person.firstName} ${person.lastName}
${entries.join('\n') || 'Für diese Woche liegen keine Tagesdaten vor.'}`,
        link: {
          label: 'Schülerprofil mit dieser Woche öffnen',
          to: {
            name: 'schueler-detail',
            params: { studentId: person.id },
            query: { klasse: person.classId, woche: v.date }
          }
        }
      }
    }
  },
  create_student: {
    title: 'Schüler anlegen',
    fields: [cls(true), ...studentDetails],
    needs: ['classes', 'staff'],
    run: (v) =>
      write(
        `${classPath(v)}/students`,
        'POST',
        { ...details(v), birthDate: v.birthDate },
        'Schüler angelegt.'
      )
  },
  update_student: {
    title: 'Schülerstammdaten ändern',
    fields: [cls(true), student, ...studentDetails],
    needs: ['classes', 'staff', 'students'],
    run: (v, ctx) => {
      const current = currentStudent(v, ctx)
      return write(
        studentPath(v),
        'PUT',
        {
          firstName: v.firstName || current.firstName,
          lastName: v.lastName || current.lastName,
          birthDate: v.birthDate || current.birthDate
        },
        'Schülerstammdaten gespeichert.'
      )
    }
  },
  delete_student: {
    title: 'Schüler löschen',
    fields: [cls(true), student],
    danger: true,
    needs: ['classes', 'staff', 'students'],
    note: 'Der Schüler wird aus der Klassenliste entfernt. Bestehende Anwesenheitsdaten können das Löschen verhindern.',
    run: (v) => write(studentPath(v), 'DELETE', null, 'Schüler gelöscht.')
  },
  add_absence_minutes: attendanceCommand(
    'Fehlminuten hinzufügen',
    [{ key: 'minutes', label: 'Unentschuldigte Minuten', kind: 'number' }],
    'manual-absence-minutes',
    (v) => ({ minutes: Number(v.minutes), requestId: v.requestId }),
    'Fehlminuten gebucht.'
  ),
  excuse_open: attendanceCommand(
    'Offene Fehlzeiten entschuldigen',
    [],
    'open-absence-excuses',
    () => ({}),
    'Offene Fehlzeiten entschuldigt.'
  ),
  excuse_school_day: attendanceCommand(
    'Ganzen Schultag im Profil entschuldigen',
    [],
    'school-day-excuses',
    () => ({}),
    'Ganztägige Entschuldigung gespeichert.'
  ),
  correct_status: attendanceCommand(
    'Tagesstatus korrigieren',
    [status, startsAt],
    'status-corrections',
    (v) => ({
      status: v.status,
      occurredAt: berlinInstant(v.date!, v.startsAt!)
    }),
    'Tagesstatus gespeichert.'
  ),
  correct_period: attendanceCommand(
    'Status für einen Zeitraum ändern',
    [status, startsAt, endsAt],
    'status-periods',
    (v) => ({ status: v.status, startsAt: v.startsAt, endsAt: v.endsAt }),
    'Status für den Zeitraum gespeichert.'
  ),
  create_class: {
    title: 'Klasse anlegen',
    fields: [classCode, plan, timetable],
    needs: ['options', 'teachers'],
    run: (v, ctx) =>
      write(
        '/api/classes',
        'POST',
        {
          classCode: v.name,
          blockPlanId: Number(v.blockPlan),
          timetableId: Number(v.timetable),
          ...(ctx.admin ? { classTeacherId: Number(v.teacher) } : {})
        },
        'Klasse angelegt.'
      )
  },
  update_class: {
    title: 'Klasse bearbeiten',
    admin: true,
    fields: [cls(), classCode, teacher()],
    needs: ['classes', 'teachers'],
    run: (v) =>
      write(
        classPath(v),
        'PUT',
        { classCode: v.name, classTeacherId: Number(v.teacher) },
        'Klasse gespeichert.'
      )
  },
  delete_class: {
    title: 'Klasse löschen',
    admin: true,
    danger: true,
    fields: [cls()],
    needs: ['classes'],
    note: 'Die App kann nur leere Klassen löschen.',
    run: (v) => write(classPath(v), 'DELETE', null, 'Klasse gelöscht.')
  },
  change_block_plan: {
    title: 'Blockplan der Klasse wechseln',
    danger: true,
    fields: [cls(true), nextPlan],
    needs: ['classes', 'staff', 'options'],
    note: 'Beim Planwechsel werden die Fehlzeitenkonten der Klasse abschließend zurückgesetzt.',
    run: async (v, ctx) => {
      await changeBlockPlan(
        Number(v.classCode),
        currentClass(v, ctx).blockPlanId,
        Number(v.blockPlan)
      )
      return result('Blockplan gewechselt.')
    }
  },
  reset_accounts: {
    title: 'Fehlzeitenkonten zurücksetzen',
    admin: true,
    danger: true,
    fields: [cls()],
    needs: ['classes'],
    note: 'Die Fehlzeitenkonten aller Schüler dieser Klasse werden zurückgesetzt.',
    run: async (v, ctx) =>
      result(
        `${await resetAccounts(Number(v.classCode), currentClass(v, ctx).blockPlanId)} Fehlzeitenkonten zurückgesetzt.`
      )
  },
  create_timetable: {
    title: 'Stundenplan anlegen',
    admin: true,
    fields: [text('name', 'Name', 150)],
    needs: [],
    run: async (v) => {
      await createTimetable(v.name!)
      return result('Stundenplan angelegt.')
    }
  },
  add_slot: {
    title: 'Unterrichtszeit hinzufügen',
    admin: true,
    fields: [timetable, ...slotFields],
    needs: ['planning'],
    run: async (v) => {
      await addSlot(Number(v.timetable), slotBody(v))
      return result('Unterrichtszeit hinzugefügt.')
    }
  },
  update_slot: {
    title: 'Unterrichtszeit ändern',
    admin: true,
    fields: [timetable, currentSlot, ...slotFields],
    needs: ['planning'],
    note: 'Die Änderung gilt ab einem Unterrichtstermin, der noch nicht begonnen hat.',
    run: async (v) =>
      result(
        `Unterrichtszeit geändert ab ${formatDate(await updateSlot(Number(v.timetable), Number(v.slot), slotBody(v)))}.`
      )
  },
  delete_slot: {
    title: 'Unterrichtszeit löschen',
    admin: true,
    danger: true,
    fields: [timetable, currentSlot],
    needs: ['planning'],
    note: 'Zukünftige Unterrichtstermine dieser Unterrichtszeit entfallen.',
    run: async (v) =>
      result(
        `Unterrichtszeit entfällt ab ${formatDate(await deleteSlot(Number(v.timetable), Number(v.slot)))}.`
      )
  },
  create_block_plan: {
    title: 'Blockplan anlegen',
    admin: true,
    fields: [text('name', 'Name', 150), ...period],
    needs: [],
    run: async (v) => {
      await createBlockPlan({
        name: v.name!,
        startsOn: v.date!,
        endsOn: v.endDate!
      })
      return result('Blockplan angelegt.')
    }
  },
  add_block: {
    title: 'Unterrichtsblock hinzufügen',
    admin: true,
    fields: [plan, ...period],
    needs: ['options'],
    run: async (v) => {
      await addBlock(Number(v.blockPlan), {
        startsOn: v.date!,
        endsOn: v.endDate!
      })
      return result('Unterrichtsblock hinzugefügt.')
    }
  },
  assign_block: {
    title: 'Klasse einem Unterrichtsblock zuordnen',
    admin: true,
    fields: [cls(), block],
    needs: ['classes', 'planning'],
    run: async (v) => {
      await assignBlock(Number(v.classCode), Number(v.block))
      return result('Klasse dem Block zugeordnet.')
    }
  },
  create_room: {
    title: 'Raum anlegen',
    admin: true,
    fields: [text('name', 'Raumnummer', 30)],
    needs: [],
    run: (v) =>
      write('/api/rooms', 'POST', { roomNumber: v.name }, 'Raum angelegt.')
  },
  update_room: {
    title: 'Raum umbenennen',
    admin: true,
    fields: [room(), text('name', 'Neue Raumnummer', 30)],
    needs: ['rooms'],
    run: (v) =>
      write(
        `/api/rooms/${Number(v.room)}`,
        'PUT',
        { roomNumber: v.name },
        'Raum gespeichert.'
      )
  },
  delete_room: {
    title: 'Raum löschen',
    admin: true,
    danger: true,
    fields: [room()],
    needs: ['rooms'],
    run: (v) =>
      write(`/api/rooms/${Number(v.room)}`, 'DELETE', null, 'Raum gelöscht.')
  },
  create_teacher: {
    title: 'Lehrkraft anlegen',
    admin: true,
    fields: [
      ...names,
      text('username', 'Benutzername'),
      { key: 'password', label: 'Startpasswort', kind: 'secret', length: 72 }
    ],
    needs: [],
    run: (v) =>
      write(
        '/api/teachers',
        'POST',
        { ...details(v), username: v.username, password: v.password },
        'Lehrkraft angelegt.'
      )
  },
  update_teacher: {
    title: 'Lehrkraft bearbeiten',
    admin: true,
    fields: [teacher('Lehrkraft'), ...names],
    needs: ['teachers'],
    run: (v) =>
      write(
        `/api/teachers/${Number(v.teacher)}`,
        'PUT',
        details(v),
        'Lehrkraft gespeichert.'
      )
  },
  delete_teacher: {
    title: 'Lehrkraft löschen',
    admin: true,
    danger: true,
    fields: [teacher('Lehrkraft')],
    needs: ['teachers'],
    run: (v) =>
      write(
        `/api/teachers/${Number(v.teacher)}`,
        'DELETE',
        null,
        'Lehrkraft gelöscht.'
      )
  },
  assign_rfid: {
    title: 'RFID-Karte zuordnen',
    admin: true,
    fields: [
      cls(),
      student,
      { key: 'uid', label: 'Kartenkennung', kind: 'secret', length: 64 }
    ],
    needs: ['classes', 'students'],
    note: 'Eine vorhandene Kartenzuordnung des Schülers wird ersetzt.',
    run: (v) =>
      write(
        `${studentPath(v)}/rfid-uid`,
        'PUT',
        { uid: v.uid },
        'RFID-Karte zugeordnet.'
      )
  },
  remove_rfid: {
    title: 'RFID-Kartenzuordnung entfernen',
    admin: true,
    danger: true,
    fields: [cls(), student],
    needs: ['classes', 'students'],
    run: (v) =>
      write(
        `${studentPath(v)}/rfid-uid`,
        'DELETE',
        null,
        'Kartenzuordnung entfernt.'
      )
  }
}

export function commandFor(key: string) {
  return Object.hasOwn(commands, key) ? commands[key] : undefined
}

export function commandFields(
  key: string,
  definition: Definition,
  admin: boolean
) {
  return key === 'create_class' && admin
    ? [...definition.fields, teacher()]
    : definition.fields
}

export async function loadCommandContext(
  definition: Definition,
  admin: boolean,
  signal: AbortSignal
): Promise<CommandContext> {
  if (definition.admin && !admin)
    throw new Error('Diese Aktion steht der Verwaltung zur Verfügung.')
  const needs = new Set(definition.needs)
  const [classes, students, staffId, options, planning, rooms, teachers] =
    await Promise.all([
      needs.has('classes')
        ? loadData<ClassRecord[]>('/api/classes', signal)
        : [],
      needs.has('students') ? loadData<Student[]>('/api/students', signal) : [],
      needs.has('staff') && !admin
        ? loadData<number>('/api/staff-id', signal)
        : null,
      needs.has('options')
        ? loadData<{ blockPlans: NamedOption[]; timetables: NamedOption[] }>(
            '/api/class-options',
            signal
          )
        : undefined,
      needs.has('planning') ? loadOverview() : undefined,
      needs.has('rooms') ? loadData<Room[]>('/api/rooms', signal) : [],
      needs.has('teachers') && admin
        ? loadData<Teacher[]>('/api/teachers', signal)
        : []
    ])
  return {
    classes,
    students,
    staffId,
    admin,
    teachers,
    planning,
    plans: options?.blockPlans ?? planning?.blockPlans ?? [],
    timetables: options?.timetables ?? planning?.timetables ?? [],
    rooms: planning?.rooms ?? rooms
  }
}

const normalized = (value: string) =>
  value.trim().normalize().toLocaleLowerCase('de-DE').replace(/\s+/g, ' ')

function matching(options: Choice[], hint: string | null | undefined) {
  if (!hint) return undefined
  const matches = options.filter((item) =>
    [item.label, ...(item.aliases ?? [])].some(
      (label) => normalized(label) === normalized(hint)
    )
  )
  return matches.length === 1 ? matches[0]?.value : undefined
}

export function fillCommand(
  fields: Field[],
  hint: CommandHint['fields'],
  ctx: CommandContext
) {
  const values: Values = {}
  // Eindeutige Personen können anhand des Namens ihrer zugänglichen Klasse zugeordnet werden.
  if (!hint.classCode && hint.studentName) {
    const target = normalized(hint.studentName)
    const candidates = ctx.students.filter((s) =>
      [studentLabel(s), `${s.firstName} ${s.lastName}`].some(
        (name) => normalized(name) === target
      )
    )
    if (candidates.length === 1) {
      const found = ctx.classes.find((c) => c.id === candidates[0]?.classId)
      if (found) hint = { ...hint, classCode: found.classCode }
    }
  }
  for (const field of fields) {
    const options = field.options?.(ctx, values)
    if (options) {
      values[field.key] = matching(options, hint[field.key]) ?? ''
    } else if (field.kind !== 'secret') {
      values[field.key] =
        hint[field.key]?.trim().slice(0, field.length ?? 100) ?? ''
    }
  }
  return values
}

export function selectedSlot(
  values: Values,
  ctx: CommandContext
): Slot | undefined {
  return ctx.planning?.timetables
    .find((item) => item.id === Number(values.timetable))
    ?.slots.find((item) => item.id === Number(values.slot))
}
