import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { routeLocationKey } from 'vue-router'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { errorMessage, type PlanningOverview } from '@/api/planning'
import { formatCalendarWeek } from '@/dateTime'
import { useAuthStore } from '@/stores/auth'
import SchedulePlanningView from './SchedulePlanningView.vue'

const overview: PlanningOverview = {
  timetables: [
    {
      id: 1,
      name: 'Stundenplan A',
      slots: [
        {
          id: 1,
          weekday: 'MONTAG',
          startsAt: '07:30:00',
          endsAt: '09:00:00',
          roomId: 1,
          roomNumber: 'R01',
          validFrom: null
        }
      ]
    }
  ],
  blockPlans: [
    {
      id: 1,
      name: 'Blockplan 2026',
      startsOn: '2026-09-01',
      endsOn: '2026-12-31',
      deleteAfter: '2027-06-30',
      blocks: [
        { id: 1, startsOn: '2026-09-14', endsOn: '2026-09-18', classIds: [1] },
        { id: 2, startsOn: '2026-09-21', endsOn: '2026-09-25', classIds: [] }
      ]
    },
    {
      id: 2,
      name: 'Fremder Blockplan',
      startsOn: '2026-09-01',
      endsOn: '2026-12-31',
      deleteAfter: '2027-06-30',
      blocks: [
        { id: 3, startsOn: '2026-10-05', endsOn: '2026-10-09', classIds: [] }
      ]
    }
  ],
  rooms: [
    { id: 1, roomNumber: 'R01' },
    { id: 2, roomNumber: 'R02' }
  ],
  classes: [{ id: 1, classCode: 'KL-A', blockPlanId: 1, timetableId: 1 }]
}

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function mountAs(role: 'admin' | 'lehrkraft') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().user = { name: 'Test', role }
  return mount(SchedulePlanningView, {
    global: {
      plugins: [pinia, PrimeVue],
      stubs: { ClassTimetableWeek: role === 'admin' },
      provide: {
        [routeLocationKey as symbol]: {
          query: { klasse: '1', datum: '2026-09-14' }
        }
      }
    }
  })
}

describe('SchedulePlanningView', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  it('zeigt Lehrkräften den Kalenderstundenplan ihrer Klassen ohne Bearbeitung', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(200, [{ id: 1, classCode: 'KL-A' }]))
      .mockResolvedValueOnce(
        respond(200, {
          schoolYear: 2026,
          periods: [],
          regularWeek: [],
          blocks: [{ startsOn: '2026-09-14', endsOn: '2026-09-18' }]
        })
      )
      .mockResolvedValueOnce(
        respond(200, [
          {
            date: '2026-09-14',
            lessons: [
              {
                startsAt: '07:30:00',
                endsAt: '09:00:00',
                roomNumber: 'R01',
                learningField: 'LF 5 · Software entwickeln',
                teacherName: 'Lehrkraft Muster'
              }
            ]
          },
          { date: '2026-09-15', lessons: [] },
          { date: '2026-09-16', lessons: [] },
          { date: '2026-09-17', lessons: [] },
          { date: '2026-09-18', lessons: [] }
        ])
      )
    const wrapper = mountAs('lehrkraft')
    await flushPromises()

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/classes',
      '/api/planning/classes/1/schedule',
      '/api/planning/classes/1/week?date=2026-09-14'
    ])
    const table = wrapper.get('table')
    expect(table.text()).toContain('Montag')
    expect(table.text()).toContain('Freitag')
    expect(table.text()).toContain('LF 5 · Software entwickeln')
    expect(table.text()).toContain('Lehrkraft Muster')
    expect(table.find('td[rowspan="2"]').exists()).toBe(true)
    expect(table.text()).not.toContain('Raum R01')
    expect(wrapper.text()).toContain('Raum R01')
    expect(wrapper.text()).toContain('38. KW 2026')
    expect(wrapper.text()).toContain('Schuljahr 2026/27')
    expect(wrapper.text()).not.toContain('Stundenpläne bearbeiten')
    wrapper.unmount()
  })

  it('zeigt nummerierte Freistunden und verbindet gleiche Unterrichtsblöcke über kurze Pausen', async () => {
    const firstLesson = {
      startsAt: '07:30',
      endsAt: '08:15',
      roomNumber: 'A 213',
      learningField: 'LF',
      teacherName: 'Lehrkraft Beispiel'
    }
    fetchMock
      .mockResolvedValueOnce(respond(200, [{ id: 1, classCode: 'KL-TEST' }]))
      .mockResolvedValueOnce(
        respond(200, {
          schoolYear: 2026,
          periods: [],
          regularWeek: [],
          blocks: [{ startsOn: '2026-09-14', endsOn: '2026-09-18' }]
        })
      )
      .mockResolvedValueOnce(
        respond(200, [
          {
            date: '2026-09-14',
            periods: [
              { number: 1, startsAt: '07:30', endsAt: '08:15' },
              { number: 2, startsAt: '08:30', endsAt: '09:15' },
              { number: 3, startsAt: '09:15', endsAt: '10:00' }
            ],
            lessons: [
              firstLesson,
              { ...firstLesson, startsAt: '08:30', endsAt: '09:15' }
            ]
          },
          { date: '2026-09-15', lessons: [] },
          { date: '2026-09-16', lessons: [] },
          { date: '2026-09-17', lessons: [] },
          { date: '2026-09-18', lessons: [] }
        ])
      )
    const wrapper = mountAs('lehrkraft')
    try {
      await flushPromises()
      expect(wrapper.get('td[rowspan="3"]').text()).toContain(
        'Lehrkraft Beispiel'
      )
      expect(wrapper.findAll('.week-grid__lesson')).toHaveLength(1)
      expect(
        wrapper.findAll('.week-grid__period').map((period) => period.text())
      ).toEqual(['1', '2', '3'])
      expect(wrapper.get('.week-grid__room').text()).toBe('Raum A 213')
    } finally {
      wrapper.unmount()
    }
  })

  it('zeigt die Jahresvorlage in einer blockfreien Woche und springt zum nächsten Block', async () => {
    const lessons = [
      {
        startsAt: '07:30',
        endsAt: '09:00',
        roomNumber: 'A 213',
        learningField: 'LF',
        teacherName: 'Lehrkraft Beispiel'
      }
    ]
    fetchMock
      .mockResolvedValueOnce(respond(200, [{ id: 1, classCode: 'KL-TEST' }]))
      .mockResolvedValueOnce(
        respond(200, {
          schoolYear: 2026,
          periods: [],
          regularWeek: [{ lessons }],
          blocks: [{ startsOn: '2026-09-28', endsOn: '2026-10-02' }]
        })
      )
      .mockResolvedValueOnce(
        respond(200, [{ date: '2026-09-14', lessons: [] }])
      )
      .mockResolvedValueOnce(respond(200, [{ date: '2026-09-28', lessons }]))
    const wrapper = mountAs('lehrkraft')
    try {
      await flushPromises()
      expect(wrapper.text()).toContain('kein Unterrichtsblock zugeordnet')
      const mode = wrapper.get('.class-week__views button:last-child')
      await mode.trigger('click')
      expect(mode.attributes('aria-pressed')).toBe('true')
      expect(wrapper.get('table').text()).toContain('Lehrkraft Beispiel')
      await wrapper.get('.class-week__controls select').setValue('2026-09-28')
      await flushPromises()
      expect(fetchMock.mock.lastCall?.[0]).toBe(
        '/api/planning/classes/1/week?date=2026-09-28'
      )
      expect(wrapper.text()).toContain('40. KW 2026')
      expect(wrapper.text()).not.toContain('kein Unterrichtsblock zugeordnet')
    } finally {
      wrapper.unmount()
    }
  })

  it('zeigt Slots, Blöcke mit Klassen und das berechnete Löschdatum', async () => {
    fetchMock.mockResolvedValue(respond(200, overview))
    const wrapper = mountAs('admin')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/planning', expect.anything())
    expect(wrapper.text()).toContain('Montag')
    expect(wrapper.text()).toContain('07:30')
    expect(wrapper.text()).toContain('R01')
    expect(wrapper.text()).toContain('30.06.2027')
    expect(wrapper.text()).toContain('KL-A')
  })

  it('bietet zur Zuordnung nur noch nicht zugeordnete Blöcke des aktuellen Plans an', async () => {
    fetchMock.mockResolvedValue(respond(200, overview))
    const wrapper = mountAs('admin')
    await flushPromises()

    const options = wrapper
      .findAll('form')
      .find((form) => form.text().includes('Block zuordnen'))!
      .findAll('option')
    expect(
      options.map((option) => [option.element.value, option.text()])
    ).toEqual([['2', '21.09.2026 – 25.09.2026']])
  })

  it('zeigt den Konflikt des Backends als verständliche Meldung an', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(200, overview))
      .mockResolvedValueOnce(
        respond(409, { code: 'OVERLAPPING_TIMETABLE_SLOT' })
      )
    const wrapper = mountAs('admin')
    await flushPromises()

    const form = wrapper
      .findAll('form')
      .find((candidate) =>
        candidate.text().includes('Unterrichtszeit hinzufügen')
      )!
    await form.find('select').setValue('DIENSTAG')
    await form.find('input[aria-label="Beginn"]').setValue('08:00')
    await form.find('input[aria-label="Ende"]').setValue('08:45')
    await form.findAll('select')[1].setValue('1')
    await form.trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/planning/timetables/1/slots',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          weekday: 'DIENSTAG',
          startsAt: '08:00',
          endsAt: '08:45',
          roomId: 1
        })
      })
    )
    expect(wrapper.find('[role="alert"]').text()).toBe(
      'Die Unterrichtszeit überschneidet sich mit einer vorhandenen Unterrichtszeit dieses Stundenplans.'
    )
    expect(form.find('input[aria-label="Beginn"]').element.value).toBe('08:00')
  })

  it('füllt die Slotbearbeitung vor und zeigt den ersten geänderten Termin', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(200, overview))
      .mockResolvedValueOnce(respond(200, { effectiveOn: '2026-09-22' }))
      .mockResolvedValueOnce(respond(200, overview))
    const wrapper = mountAs('admin')
    await flushPromises()

    await wrapper
      .get('button[aria-label="Unterrichtszeit Montag 07:30 bearbeiten"]')
      .trigger('click')
    const form = wrapper
      .findAll('form')
      .find((candidate) =>
        candidate.text().includes('Unterrichtszeit bearbeiten')
      )!
    const [weekday, room] = form.findAll('select')
    const startsAt = form.get('input[aria-label="Beginn"]')
    const endsAt = form.get('input[aria-label="Ende"]')
    expect([
      weekday.element.value,
      startsAt.element.value,
      endsAt.element.value,
      room.element.value
    ]).toEqual(['MONTAG', '07:30:00', '09:00:00', '1'])
    await weekday.setValue('DIENSTAG')
    await startsAt.setValue('08:00')
    await endsAt.setValue('09:30')
    await room.setValue('2')
    await form.trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/planning/timetables/1/slots/1',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          weekday: 'DIENSTAG',
          startsAt: '08:00',
          endsAt: '09:30',
          roomId: 2
        })
      })
    )
    expect(wrapper.get('[role="status"]').text()).toBe(
      'Unterrichtszeit geändert ab 22.09.2026.'
    )
  })

  it('löscht zukünftige Termine erst nach Bestätigung', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(200, overview))
      .mockResolvedValueOnce(respond(200, { effectiveOn: '2026-09-21' }))
      .mockResolvedValueOnce(respond(200, overview))
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    const wrapper = mountAs('admin')
    await flushPromises()

    const button = wrapper.get(
      'button[aria-label="Unterrichtszeit Montag 07:30 löschen"]'
    )
    await button.trigger('click')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await button.trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/planning/timetables/1/slots/1',
      expect.objectContaining({ method: 'DELETE' })
    )
    expect(wrapper.get('[role="status"]').text()).toBe(
      'Unterrichtszeit entfällt ab 21.09.2026.'
    )
  })

  it('berechnet die Kalenderwoche auch beim Jahreswechsel nach ISO', () => {
    expect(formatCalendarWeek('2027-01-01')).toBe('53. KW 2026')
    expect(formatCalendarWeek('2027-01-04')).toBe('1. KW 2027')
  })

  it('liefert für unbekannte Fehlercodes einen allgemeinen Hinweis', () => {
    expect(errorMessage('UNBEKANNT')).toContain('fehlgeschlagen')
    expect(errorMessage(undefined)).toContain('fehlgeschlagen')
  })
})
