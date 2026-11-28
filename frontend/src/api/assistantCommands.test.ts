import { describe, expect, it, vi } from 'vitest'

import {
  attendanceSummary,
  commands,
  type CommandContext
} from './assistantCommands'
import type { DailyStudent } from './attendance'

function student(index: number, status: DailyStudent['status']): DailyStudent {
  return {
    studentId: index,
    firstName: 'Kind',
    lastName: `Nr${String(index).padStart(2, '0')}`,
    status,
    unexcusedMinutes: 0,
    wasPresent: false,
    fullAbsence: null,
    manualAbsenceMinutes: 0
  }
}

describe('Tagesanwesenheit im Chat', () => {
  it('nennt alle Abwesenden und trennt unentschuldigt von entschuldigt', () => {
    const records = [
      ...Array.from({ length: 14 }, (_, index) => student(index, 'ABWESEND')),
      student(20, 'MIT_ATTEST_ENTSCHULDIGT'),
      student(21, 'ENTSCHULDIGT'),
      student(22, 'BETRIEB'),
      student(23, 'ANWESEND')
    ]
    const lines = attendanceSummary(records).split('\n')

    expect(lines[0]).toMatch(/^Unentschuldigt abwesend \(14\): /)
    expect(lines[0]).toContain('Kind Nr13')
    expect(lines[0]).not.toContain('…')
    expect(lines[1]).toBe('Entschuldigt (2): Kind Nr20 (mit Attest), Kind Nr21')
    expect(lines[2]).toBe('Im Betrieb (1): Kind Nr22')
    expect(lines[3]).toBe('Anwesend: 1 von 18')
  })

  it('sagt ausdrücklich, wenn niemand fehlt oder keine Daten vorliegen', () => {
    expect(attendanceSummary([student(1, 'ANWESEND')])).toBe(
      'Niemand ist als abwesend oder entschuldigt eingetragen.\nAnwesend: 1 von 1'
    )
    expect(attendanceSummary([])).toBe(
      'Für diesen Tag sind keine Anwesenheitsdaten verfügbar.'
    )
  })
})

describe('Schreibende Aufträge im Chat', () => {
  const context = {
    classes: [{ id: 2, classCode: 'FI26A', classTeacherId: 4 }],
    students: [],
    staffId: 4,
    plans: [],
    timetables: [],
    rooms: [],
    teachers: [],
    admin: true
  } as unknown as CommandContext

  it.each([
    [
      'create_class',
      { name: 'FI26A', blockPlan: '1', timetable: '1', teacher: '4' },
      409,
      'CLASS_CODE_ALREADY_EXISTS',
      'Dieses Klassenkürzel ist bereits vergeben.'
    ],
    [
      'add_absence_minutes',
      {
        classCode: '2',
        studentName: '7',
        date: '2025-09-15',
        minutes: '600',
        note: 'Früher gegangen'
      },
      400,
      'MANUAL_ABSENCE_EXCEEDS_SCHOOL_DAY',
      'Das sind mehr Fehlminuten, als an diesem Tag noch offen sein können'
    ]
  ])(
    'nennt bei %s den Grund statt eines allgemeinen Fehlers',
    async (key, values, status, code, message) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => Response.json({ code }, { status }))
      )
      await expect(commands[key]!.run(values, context)).rejects.toThrow(message)
    }
  )

  it('meldet eine bereits gespeicherte Buchung nicht als neu', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ changed: false }))
    )
    const result = await commands.excuse_open!.run(
      { classCode: '2', studentName: '7', date: '2025-09-15' },
      context
    )
    expect(result.text).toBe('Die Angaben waren bereits so gespeichert.')
  })
})
