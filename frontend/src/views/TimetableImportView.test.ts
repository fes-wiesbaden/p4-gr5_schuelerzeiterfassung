import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { timetableDraft } from '@/api/timetableImport'
import TimetableImportView from './TimetableImportView.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'planung', component: { template: '<div />' } },
    { path: '/entwurf', component: TimetableImportView }
  ]
})

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  timetableDraft.value = undefined
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

function draft() {
  return {
    name: '10/11/12BE13 · aus Bild',
    notes: ['Do 14:45: Fach nur teilweise lesbar'],
    rooms: [{ id: 1, roomNumber: 'A 213' }],
    teachers: [{ id: 8, name: 'Sommerfeld' }],
    classCode: '10/11/12BE13',
    classId: null,
    classes: [],
    blockPlans: [
      {
        id: 3,
        name: 'Schuljahr 2026/27',
        startsOn: '2026-08-01',
        endsOn: '2027-07-31'
      }
    ],
    target: {
      mode: 'new' as const,
      classCode: '10/11/12BE13',
      classTeacherId: 8,
      blockPlanId: 3,
      validFrom: '2999-01-04',
      validUntil: '2999-01-22'
    },
    slots: [
      {
        weekday: 'MONTAG' as const,
        startsAt: '07:30',
        endsAt: '09:00',
        subject: 'LF',
        teacherName: 'Sommer',
        roomNumber: 'A 213',
        teacherId: 8,
        roomId: 1
      },
      {
        weekday: 'MONTAG' as const,
        startsAt: '09:15',
        endsAt: '10:45',
        subject: 'SPO',
        teacherName: 'Arjoune',
        roomNumber: 'Sport 9',
        teacherId: null,
        roomId: null
      }
    ]
  }
}

it('legt den Entwurf erst nach Korrektur und Bestätigung an', async () => {
  document.cookie = 'XSRF-TOKEN=csrf-test'
  timetableDraft.value = draft()
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ id: 9 }))
  vi.stubGlobal('fetch', fetchMock)
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  const wrapper = mount(TimetableImportView, {
    global: { plugins: [router, PrimeVue] }
  })

  expect(wrapper.text()).toContain('Noch ist nichts gespeichert.')
  expect(wrapper.text()).toContain('Fach nur teilweise lesbar')
  expect(wrapper.findAll('.import__row--problem')).toHaveLength(1)

  // Ohne zugeordneten Raum wird nichts gesendet und nicht nachgefragt
  await wrapper.get('.import__actions button').trigger('click')
  expect(confirm).not.toHaveBeenCalled()
  expect(fetchMock).not.toHaveBeenCalled()

  await wrapper.findAll('.import__row')[1]!.findAll('select')[2]!.setValue(1)
  expect(wrapper.find('.import__row--problem').exists()).toBe(false)
  await wrapper.get('.import__actions button').trigger('click')
  await flushPromises()

  expect(confirm).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledWith(
    '/api/planning/timetables/import',
    expect.objectContaining({ method: 'POST' })
  )
  expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
    name: '10/11/12BE13 · aus Bild',
    slots: [
      {
        weekday: 'MONTAG',
        startsAt: '07:30',
        endsAt: '09:00',
        roomId: 1,
        subject: 'LF',
        teacherId: 8
      },
      {
        weekday: 'MONTAG',
        startsAt: '09:15',
        endsAt: '10:45',
        roomId: 1,
        subject: 'SPO',
        teacherId: null
      }
    ],
    schoolClass: {
      classCode: '10/11/12BE13',
      classTeacherId: 8,
      blockPlanId: 3,
      validFrom: '2999-01-04',
      validUntil: '2999-01-22'
    }
  })
  expect(timetableDraft.value).toBeUndefined()
  expect(wrapper.text()).toContain('ist angelegt')
  wrapper.unmount()
})

it('markiert die vom Server abgelehnte Zeile und behält den Entwurf', async () => {
  timetableDraft.value = draft()
  timetableDraft.value.slots[1]!.roomId = 1
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { code: 'OVERLAPPING_TIMETABLE_SLOT', slotIndex: 1 },
          { status: 400 }
        )
      )
  )
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const wrapper = mount(TimetableImportView, {
    global: { plugins: [router, PrimeVue] }
  })

  await wrapper.get('.import__actions button').trigger('click')
  await flushPromises()

  expect(timetableDraft.value?.slots).toHaveLength(2)
  expect(
    wrapper.findAll('.import__row')[1]!.classes('import__row--problem')
  ).toBe(true)
  wrapper.unmount()
})

it('übernimmt einen neuen Entwurf aus dem Chat auch bei offener Seite', async () => {
  timetableDraft.value = draft()
  const wrapper = mount(TimetableImportView, {
    global: { plugins: [router, PrimeVue] }
  })
  const mode = () => wrapper.get<HTMLSelectElement>('.import__mode select')
  expect(mode().element.value).toBe('new')

  timetableDraft.value = {
    ...draft(),
    classes: [{ id: 4, classCode: '11A' }],
    target: { mode: 'existing', classId: 4, validFrom: '2999-02-01' }
  }
  await flushPromises()

  expect(mode().element.value).toBe('existing')
  expect(
    wrapper.get<HTMLSelectElement>('.import__class-fields select').element.value
  ).toBe('4')
  wrapper.unmount()
})
