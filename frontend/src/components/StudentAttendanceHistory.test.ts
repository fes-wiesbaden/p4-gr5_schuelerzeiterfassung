import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, expect, it, vi } from 'vitest'
import StudentAttendanceHistory from './StudentAttendanceHistory.vue'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

it('zeigt die Woche eines Schülers und entschuldigt alle offenen Fehlzeiten des gewählten Tages', async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-15T07:00:00Z'))
  document.cookie = 'XSRF-TOKEN=csrf-test'
  const monday = {
    date: '2026-09-14',
    unexcusedMinutes: 20,
    excusedMinutes: 0,
    lateMinutes: 20,
    didNotArrive: false,
    absenceLevel: 'PARTIAL',
    editable: true,
    manualAbsenceMinutes: 0,
    canExcuseOpenAbsences: true
  }
  const week = {
    startsOn: '2026-09-14',
    endsOn: '2026-09-20',
    days: [monday]
  }
  let resolveWeek!: (response: Response) => void
  const refreshedWeek = new Promise<Response>((resolve) => {
    resolveWeek = resolve
  })
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(Response.json(week))
    .mockResolvedValueOnce(Response.json({ changed: true }))
    .mockReturnValueOnce(refreshedWeek)
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {}
  }))
  const wrapper = mount(StudentAttendanceHistory, {
    props: { studentId: 4 },
    global: { plugins: [PrimeVue] }
  })

  await flushPromises()
  const url = '/api/students/4/attendance/week?date=2026-09-15'
  expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([url])
  expect(wrapper.get('tr.history-partial').text()).toContain('14.09.2026')
  expect(wrapper.get('tr.history-partial').classes()).toContain(
    'history-editable'
  )
  await wrapper.get('button.day-select').trigger('click')
  await wrapper.get('form select').setValue('excuse')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(fetchMock).toHaveBeenNthCalledWith(
    2,
    '/api/students/4/attendance/2026-09-14/open-absence-excuses',
    expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-test' }),
      body: '{}'
    })
  )
  expect(fetchMock.mock.calls[2]?.[0]).toBe(url)
  expect(wrapper.get('tr.history-partial').exists()).toBe(true)
  expect(wrapper.find('[role="status"]').exists()).toBe(false)

  resolveWeek(
    Response.json({
      ...week,
      days: [
        {
          ...monday,
          unexcusedMinutes: 0,
          excusedMinutes: 20,
          absenceLevel: null,
          canExcuseOpenAbsences: false
        }
      ]
    })
  )
  await flushPromises()

  expect(wrapper.find('[role="status"]').exists()).toBe(false)
  expect(wrapper.find('option[value="excuse"]').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('Fehlzeitabschnitte')
  expect(wrapper.find('tr.history-partial').exists()).toBe(false)
  expect(
    wrapper
      .findAll('tbody td')
      .map((cell) => cell.text())
      .slice(1, 5)
  ).toEqual(['20 min', '20 min', '0 min', '20 min'])
  wrapper.unmount()
})

it('blättert zwei Kalenderwochen vor und merkt nur die erlaubten Status vor', async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-15T07:00:00Z'))
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {}
  }))
  const day = {
    date: '2026-09-28',
    unexcusedMinutes: null,
    excusedMinutes: null,
    lateMinutes: null,
    didNotArrive: false,
    absenceLevel: null,
    editable: false,
    canExcuseOpenAbsences: false,
    manualAbsenceMinutes: 0,
    canCorrectStatus: false,
    canPlanStatus: true,
    plannedStatuses: []
  }
  const lastWeek = { startsOn: '2026-09-28', endsOn: '2026-10-04', days: [day] }
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ startsOn: '2026-09-14', endsOn: '2026-09-20', days: [] })
    )
    .mockResolvedValueOnce(
      Response.json({ startsOn: '2026-09-21', endsOn: '2026-09-27', days: [] })
    )
    .mockResolvedValueOnce(Response.json(lastWeek))
    .mockResolvedValueOnce(Response.json({ changed: true }))
    .mockResolvedValueOnce(
      Response.json({
        ...lastWeek,
        days: [
          {
            ...day,
            plannedStatuses: [
              {
                startsAt: '09:30:00',
                endsAt: '11:00:00',
                status: 'BETRIEBLICH_ENTSCHULDIGT'
              }
            ]
          }
        ]
      })
    )
  vi.stubGlobal('fetch', fetchMock)
  const wrapper = mount(StudentAttendanceHistory, {
    props: { studentId: 4 },
    global: { plugins: [PrimeVue] }
  })
  await flushPromises()
  const nextWeek = wrapper.get<HTMLButtonElement>(
    'button[aria-label="Nächste Woche"]'
  )
  for (const date of ['2026-09-22', '2026-09-29']) {
    expect(nextWeek.element.disabled).toBe(false)
    await nextWeek.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.lastCall?.[0]).toBe(
      `/api/students/4/attendance/week?date=${date}`
    )
  }
  expect(nextWeek.element.disabled).toBe(true)
  await wrapper.get('button.day-select').trigger('click')
  const [action, status] = wrapper.findAll('form select')
  expect(
    action?.findAll('option').map((option) => option.element.value)
  ).toEqual(['status'])
  expect(
    status?.findAll('option').map((option) => option.element.value)
  ).toEqual(['BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT'])
  const [start, end] = wrapper.findAll('form input.german-time')
  await start?.setValue('09:30')
  await end?.setValue('11:00')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(fetchMock).toHaveBeenNthCalledWith(
    4,
    '/api/students/4/attendance/2026-09-28/status-periods',
    expect.objectContaining({
      method: 'POST',
      body: '{"status":"BETRIEBLICH_ENTSCHULDIGT","startsAt":"09:30","endsAt":"11:00"}'
    })
  )
  expect(wrapper.text()).toContain(
    'Geplant 09:30–11:00 · Betrieblich entschuldigt'
  )
  await wrapper.get('form input[type="checkbox"]').setValue(true)
  expect(wrapper.find('form input.german-time').exists()).toBe(false)
  expect(wrapper.get('form button[type="submit"]').text()).toBe(
    'Schultag vormerken'
  )
  fetchMock
    .mockResolvedValueOnce(Response.json({ changed: true }))
    .mockResolvedValueOnce(Response.json(lastWeek))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(fetchMock).toHaveBeenNthCalledWith(
    6,
    '/api/students/4/attendance/2026-09-28/school-day-excuses',
    expect.objectContaining({ method: 'POST', body: '{}' })
  )
  expect(wrapper.emitted('corrected')).toBeUndefined()
  wrapper.unmount()
})
