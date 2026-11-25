import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { errorMessage, type PlanningOverview } from '@/api/planning'
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
  return mount(SchedulePlanningView, { global: { plugins: [pinia, PrimeVue] } })
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

  it('zeigt Lehrkräften nur einen Hinweis und lädt keine Planung', () => {
    const wrapper = mountAs('lehrkraft')

    expect(wrapper.text()).toContain('von Administratoren gepflegt')
    expect(fetchMock).not.toHaveBeenCalled()
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
      .find((candidate) => candidate.text().includes('Slot hinzufügen'))!
    await form.find('select').setValue('DIENSTAG')
    await form.find('input[type="time"]').setValue('08:00')
    await form.findAll('input[type="time"]')[1].setValue('08:45')
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
      'Der Slot überschneidet sich mit einem vorhandenen Slot dieses Stundenplans.'
    )
    expect(form.find('input[type="time"]').element.value).toBe('08:00')
  })

  it('füllt die Slotbearbeitung vor und zeigt den ersten geänderten Termin', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(200, overview))
      .mockResolvedValueOnce(respond(200, { effectiveOn: '2026-09-22' }))
      .mockResolvedValueOnce(respond(200, overview))
    const wrapper = mountAs('admin')
    await flushPromises()

    await wrapper
      .get('button[aria-label="Slot Montag 07:30 bearbeiten"]')
      .trigger('click')
    const form = wrapper
      .findAll('form')
      .find((candidate) => candidate.text().includes('Slot bearbeiten'))!
    const [weekday, room] = form.findAll('select')
    const [startsAt, endsAt] = form.findAll('input[type="time"]')
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
      'Slot geändert ab 22.09.2026.'
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

    const button = wrapper.get('button[aria-label="Slot Montag 07:30 löschen"]')
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
      'Slot entfällt ab 21.09.2026.'
    )
  })

  it('liefert für unbekannte Fehlercodes einen allgemeinen Hinweis', () => {
    expect(errorMessage('UNBEKANNT')).toContain('fehlgeschlagen')
    expect(errorMessage(undefined)).toContain('fehlgeschlagen')
  })
})
