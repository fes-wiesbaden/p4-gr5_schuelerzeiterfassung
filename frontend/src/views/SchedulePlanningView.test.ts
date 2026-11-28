import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { routeLocationKey } from 'vue-router'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlanningOverview } from '@/api/planning'
import { notice } from '@/notice'
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

function mountAs(role: 'admin' | 'lehrkraft', attachTo?: HTMLElement) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().user = { name: 'Test', role }
  return mount(SchedulePlanningView, {
    attachTo,
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
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    )
  })

  afterEach(() => {
    fetchMock.mockReset()
  })

  it('zeigt Lehrkräften den Kalenderstundenplan ihrer Klassen ohne Bearbeitung', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(200, [{ id: 1, classCode: 'KL-A' }]))
      .mockResolvedValueOnce(
        respond(200, {
          schoolYear: 2026,
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
                roomNumber: 'R01'
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
    expect(table.text()).toContain('Unterricht')
    expect(table.get('.week-grid__lesson').text()).toContain('Raum R01')
    expect(wrapper.text()).toContain('38. KW 2026')
    expect(wrapper.text()).toContain('Schuljahr 2026/27')
    expect(wrapper.find('[role="tablist"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('zeigt nummerierte Freistunden und trennt Unterrichtszeiten durch Pausen', async () => {
    const firstLesson = {
      startsAt: '07:30',
      endsAt: '08:15',
      roomNumber: 'A 213'
    }
    fetchMock
      .mockResolvedValueOnce(respond(200, [{ id: 1, classCode: 'KL-TEST' }]))
      .mockResolvedValueOnce(
        respond(200, {
          schoolYear: 2026,
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
              {
                ...firstLesson,
                startsAt: '08:30',
                endsAt: '09:15',
                roomNumber: 'A 214'
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
    try {
      await flushPromises()
      expect(
        wrapper.findAll('.week-grid__lesson small').map((room) => room.text())
      ).toEqual(['Raum A 213', 'Raum A 214'])
      expect(wrapper.get('table').text()).toContain('Pause')
      expect(
        wrapper.findAll('.week-grid__period').map((period) => period.text())
      ).toEqual(['1', '2', '3'])
    } finally {
      wrapper.unmount()
    }
  })

  it('zeigt den Blockzeitraum und springt aus einer blockfreien Woche zum nächsten Block', async () => {
    const lessons = [
      {
        startsAt: '07:30',
        endsAt: '09:00',
        roomNumber: 'A 213'
      }
    ]
    fetchMock
      .mockResolvedValueOnce(respond(200, [{ id: 1, classCode: 'KL-TEST' }]))
      .mockResolvedValueOnce(
        respond(200, {
          schoolYear: 2026,
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
      expect(wrapper.get('.class-week__controls select').text()).toContain(
        '28.09.2026 – 02.10.2026'
      )
      await wrapper.get('.class-week__break button').trigger('click')
      await flushPromises()
      expect(fetchMock.mock.lastCall?.[0]).toBe(
        '/api/planning/classes/1/week?date=2026-09-28'
      )
      expect(wrapper.text()).toContain('40. KW 2026')
      expect(wrapper.text()).not.toContain('kein Unterrichtsblock zugeordnet')
      expect(wrapper.get('table').text()).toContain('Unterricht')
    } finally {
      wrapper.unmount()
    }
  })

  it('startet mit dem Stundenplan und zeigt jeweils nur den gewählten Planungsbereich', async () => {
    fetchMock.mockResolvedValue(respond(200, overview))
    const wrapper = mountAs('admin')
    try {
      await flushPromises()

      expect(fetchMock).toHaveBeenCalledWith('/api/planning', expect.anything())
      const tabs = wrapper.findAll('[role="tab"]')
      const panels = wrapper.findAll('[role="tabpanel"]')
      expect(tabs.map((tab) => tab.text())).toEqual([
        'Stundenplan',
        'Blockplan',
        'Klassenplanung'
      ])
      expect(tabs[0]?.attributes('aria-selected')).toBe('true')
      expect(panels.map((panel) => panel.isVisible())).toEqual([
        true,
        false,
        false
      ])
      expect(panels[0]?.text()).toContain('Montag')
      expect(panels[0]?.text()).toContain('07:30')
      expect(panels[0]?.text()).toContain('R01')

      await tabs[1]?.trigger('click')
      expect(panels.map((panel) => panel.isVisible())).toEqual([
        false,
        true,
        false
      ])
      expect(panels[1]?.text()).toContain('30.06.2027')
      expect(panels[1]?.text()).toContain('KL-A')

      await tabs[2]?.trigger('click')
      expect(panels.map((panel) => panel.isVisible())).toEqual([
        false,
        false,
        true
      ])
      expect(panels[2]?.text()).toContain('Block zuordnen')
      expect(fetchMock).toHaveBeenCalledTimes(1)
    } finally {
      wrapper.unmount()
    }
  })

  it('lässt freie Blöcke zu und sperrt Überschneidungen desselben Stundenplans', async () => {
    const [ownPlan, otherPlan] = overview.blockPlans
    const planning: PlanningOverview = {
      ...overview,
      classes: [
        ...overview.classes,
        { id: 2, classCode: 'KL-B', blockPlanId: 2, timetableId: 1 }
      ],
      blockPlans: [
        {
          ...ownPlan!,
          blocks: [
            ...ownPlan!.blocks,
            {
              id: 4,
              startsOn: '2026-09-28',
              endsOn: '2026-10-05',
              classIds: []
            }
          ]
        },
        {
          ...otherPlan!,
          blocks: [{ ...otherPlan!.blocks[0]!, classIds: [2] }]
        }
      ]
    }
    fetchMock
      .mockResolvedValueOnce(respond(200, planning))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(respond(200, planning))
    const wrapper = mountAs('admin')
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[2]!.trigger('click')
    const form = wrapper
      .findAll('form')
      .find((form) => form.text().includes('Block zuordnen'))!
    const select = form.get('select')
    const options = select.findAll('option')
    expect(
      options.map((option) => [option.element.value, option.element.disabled])
    ).toEqual([
      ['2', false],
      ['4', true]
    ])
    expect(options[1]?.text()).toContain('Stundenplan belegt')
    await select.setValue('2')
    await form.trigger('submit')
    await flushPromises()
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/planning/classes/1/blocks',
      expect.objectContaining({ method: 'POST', body: '{"blockId":2}' })
    )
    wrapper.unmount()
  })

  it('fokussiert den Konflikt beim Speichern und springt nach oben', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    fetchMock
      .mockResolvedValueOnce(respond(200, overview))
      .mockResolvedValueOnce(
        respond(409, { code: 'OVERLAPPING_TIMETABLE_SLOT' })
      )
    const wrapper = mountAs('admin', document.body)
    await flushPromises()

    const form = wrapper
      .findAll('form')
      .find((candidate) =>
        candidate.text().includes('Unterrichtszeit hinzufügen')
      )!
    await form.find('select').setValue('DIENSTAG')
    await form.find('input[aria-label="Beginn"]').setValue('08:00')
    await form.find('input[aria-label="Ende"]').setValue('08:45')
    await form.findAll('select')[2].setValue('1')
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
          roomId: 1,
          subject: null,
          teacherId: null
        })
      })
    )
    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toBe(
      'Die Unterrichtszeit überschneidet sich mit einer vorhandenen Unterrichtszeit dieses Stundenplans.'
    )
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'instant' })
    expect(document.activeElement).toBe(alert.element)
    expect(
      form.get<HTMLInputElement>('input[aria-label="Beginn"]').element.value
    ).toBe('08:00')
    wrapper.unmount()
  })

  it.each([200, 500])(
    'ignoriert eine überholte Planungsantwort mit HTTP %i',
    async (status) => {
      let finishOldRequest!: (response: Response) => void
      const oldRequest = new Promise<Response>((resolve) => {
        finishOldRequest = resolve
      })
      const latest = {
        ...overview,
        timetables: [{ ...overview.timetables[0]!, name: 'Aktueller Plan' }]
      }
      const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
      fetchMock
        .mockReturnValueOnce(oldRequest)
        .mockResolvedValueOnce(respond(200, latest))

      const wrapper = mountAs('admin', document.body)
      try {
        window.dispatchEvent(new Event('management:changed'))
        await flushPromises()
        expect(fetchMock.mock.calls[0]?.[1].signal.aborted).toBe(true)
        expect(wrapper.text()).toContain('Aktueller Plan')

        finishOldRequest(respond(status, overview))
        await flushPromises()
        expect(wrapper.text()).toContain('Aktueller Plan')
        expect(wrapper.find('[role="alert"]').exists()).toBe(false)
        expect(scrollTo).not.toHaveBeenCalled()
      } finally {
        wrapper.unmount()
      }
    }
  )

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
    const [weekday, , room] = form.findAll('select')
    const startsAt = form.get<HTMLInputElement>('input[aria-label="Beginn"]')
    const endsAt = form.get<HTMLInputElement>('input[aria-label="Ende"]')
    expect([
      weekday.element.value,
      startsAt.element.value,
      endsAt.element.value,
      room.element.value
    ]).toEqual(['MONTAG', '07:30', '09:00', '1'])
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
          roomId: 2,
          subject: null,
          teacherId: null
        })
      })
    )
    expect(notice.value).toBe('Unterrichtszeit geändert ab 22.09.2026.')
    wrapper.unmount()
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
    expect(notice.value).toBe('Unterrichtszeit entfällt ab 21.09.2026.')
    wrapper.unmount()
  })
})
