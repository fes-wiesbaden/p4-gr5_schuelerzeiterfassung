import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LiveAttendanceView from './LiveAttendanceView.vue'

const classes = [
  { id: 1, classCode: 'KL-A' },
  { id: 2, classCode: 'KL-B' }
]

const day = [
  {
    studentId: 7,
    firstName: 'Erika',
    lastName: 'Beispiel',
    status: 'ABWESEND',
    unexcusedMinutes: 30
  }
]

function respond(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function attendanceCalls(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls
    .map(([url]) => url as string)
    .filter((url) => url.includes('/attendance?date='))
}

describe('LiveAttendanceView', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.useFakeTimers()
    // 08:00 Berliner Sommerzeit
    vi.setSystemTime(new Date('2026-09-14T06:00:00Z'))
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(async (url: string, options: RequestInit) => {
      if (url === '/api/classes') return respond(200, classes)
      if (url === '/api/attendance/current-class')
        return respond(200, { classId: 2, serverTime: '2026-09-14T06:00:00Z' })
      if (options?.method === 'POST') return respond(200, { changed: true })
      return respond(200, day)
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  async function mountView() {
    const wrapper = mount(LiveAttendanceView, {
      global: { plugins: [PrimeVue] }
    })
    await flushPromises()
    return wrapper
  }

  it('wählt die Klasse nach Stundenplan vor und zeigt deren Tagesstatus', async () => {
    const wrapper = await mountView()

    expect(
      (wrapper.find('.class-filter select').element as HTMLSelectElement).value
    ).toBe('2')
    expect(attendanceCalls(fetchMock)).toEqual([
      '/api/classes/2/attendance?date=2026-09-14'
    ])
    expect(wrapper.text()).toContain('Beispiel, Erika')
    expect(wrapper.text()).toContain('Abwesend')
    expect(wrapper.text()).toContain('30')
  })

  it('fällt ohne laufenden Unterricht auf die erste Klasse zurück', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url === '/api/classes'
        ? respond(200, classes)
        : url === '/api/attendance/current-class'
          ? respond(200, { classId: null, serverTime: '2026-09-14T06:00:00Z' })
          : respond(200, day)
    )
    await mountView()

    expect(attendanceCalls(fetchMock)).toEqual([
      '/api/classes/1/attendance?date=2026-09-14'
    ])
  })

  it('lädt alle 30 Sekunden per REST neu und stoppt beim Verlassen', async () => {
    const wrapper = await mountView()

    await vi.advanceTimersByTimeAsync(29_000)
    expect(attendanceCalls(fetchMock)).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(attendanceCalls(fetchMock)).toHaveLength(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(attendanceCalls(fetchMock)).toHaveLength(2)
  })

  it('übernimmt beim Polling neuen Status und aktuelle Uhrzeit in unbearbeitete Eingaben', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementation(async () =>
      respond(200, [{ ...day[0], status: 'ANWESEND' }])
    )

    await vi.advanceTimersByTimeAsync(30 * 60_000)

    const form = wrapper.find('form.correction')
    expect((form.find('select').element as HTMLSelectElement).value).toBe(
      'ANWESEND'
    )
    expect(
      (form.find('input[type="time"]').element as HTMLInputElement).value
    ).toBe('08:30')
  })

  it.each([
    ['den Status', 'select', 'ENTSCHULDIGT'],
    ['die Uhrzeit', 'input', '07:45']
  ])('behält bearbeitet %s beim Polling', async (_, field, value) => {
    const wrapper = await mountView()
    const input = wrapper.find(`form.correction ${field}`)
    await input.setValue(value)

    await vi.advanceTimersByTimeAsync(30 * 60_000)

    expect((input.element as HTMLInputElement).value).toBe(value)
  })

  it('richtet Tag und vorbelegte Uhrzeit an der Serverzeit statt an der Browseruhr aus', async () => {
    // Browseruhr geht 30 Minuten vor und steht schon auf dem Folgetag
    vi.setSystemTime(new Date('2026-09-14T22:20:00Z'))
    fetchMock.mockImplementation(async (url: string) =>
      url === '/api/classes'
        ? respond(200, classes)
        : url === '/api/attendance/current-class'
          ? respond(200, { classId: 2, serverTime: '2026-09-14T21:50:00Z' })
          : respond(200, day)
    )
    const wrapper = await mountView()

    expect(attendanceCalls(fetchMock)).toEqual([
      '/api/classes/2/attendance?date=2026-09-14'
    ])
    expect(
      (wrapper.find('form.correction input').element as HTMLInputElement).value
    ).toBe('23:50')
  })

  it('versucht das fehlgeschlagene Laden der Klassen beim nächsten Takt erneut', async () => {
    fetchMock.mockImplementationOnce(async () => respond(500, {}))
    const wrapper = await mountView()
    expect(wrapper.get('[role="alert"]').text()).toContain(
      'Klassen konnten nicht geladen werden'
    )

    await vi.advanceTimersByTimeAsync(30_000)

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(attendanceCalls(fetchMock)).toEqual([
      '/api/classes/2/attendance?date=2026-09-14'
    ])
  })

  it('behält das Änderungsformular, wenn das Neuladen nach einer Änderung scheitert', async () => {
    const wrapper = await mountView()
    fetchMock
      .mockImplementationOnce(async () => respond(200, { changed: true }))
      .mockImplementationOnce(async () => respond(500, {}))

    await wrapper.find('form.correction').trigger('submit')
    await flushPromises()

    expect(wrapper.find('form.correction').exists()).toBe(true)
  })

  it('pollt nicht weiter, wenn die Seite vor Ende des ersten Ladens verlassen wird', async () => {
    let finishClasses: (response: Response) => void = () => {}
    fetchMock.mockImplementationOnce(
      () => new Promise<Response>((resolve) => (finishClasses = resolve))
    )
    const wrapper = mount(LiveAttendanceView, {
      global: { plugins: [PrimeVue] }
    })

    wrapper.unmount()
    finishClasses(respond(200, classes))
    await flushPromises()
    const calls = fetchMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(90_000)

    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it('meldet einen Verbindungsabbruch bei der Statusänderung', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementationOnce(async () => {
      throw new TypeError('Failed to fetch')
    })

    await wrapper.find('form.correction').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Bitte Verbindung prüfen')
  })

  it('lädt bei manuellem Aktualisieren sofort neu', async () => {
    const wrapper = await mountView()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Aktualisieren'))!
      .trigger('click')
    await flushPromises()

    expect(attendanceCalls(fetchMock)).toHaveLength(2)
  })

  it('lädt nach Klassenwechsel die gewählte Klasse', async () => {
    const wrapper = await mountView()

    await wrapper.find('.class-filter select').setValue('1')
    await flushPromises()

    expect(attendanceCalls(fetchMock).at(-1)).toBe(
      '/api/classes/1/attendance?date=2026-09-14'
    )
  })

  it('sendet eine Statusänderung ab der gewählten Uhrzeit und lädt die Minuten neu', async () => {
    const wrapper = await mountView()

    const form = wrapper.find('form.correction')
    await form.find('select').setValue('ENTSCHULDIGT')
    await form.find('input[type="time"]').setValue('07:45')
    await form.trigger('submit')
    await flushPromises()

    const post = fetchMock.mock.calls.find(
      ([, options]) => options?.method === 'POST'
    )!
    expect(post[0]).toBe(
      '/api/students/7/attendance/2026-09-14/status-corrections'
    )
    expect(JSON.parse(post[1].body)).toEqual({
      status: 'ENTSCHULDIGT',
      occurredAt: '2026-09-14T05:45:00.000Z'
    })
    expect(attendanceCalls(fetchMock)).toHaveLength(2)
    expect(wrapper.text()).toContain('Status von Beispiel, Erika gespeichert.')
  })

  it('meldet eine Statusänderung ohne Wirkung nicht als gespeichert', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementationOnce(async () =>
      respond(200, { changed: false })
    )

    await wrapper.find('form.correction').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Keine Änderung')
    expect(wrapper.text()).not.toContain('gespeichert')
  })

  it('behält beim fehlgeschlagenen Polling die letzte Anzeige und meldet den Fehler', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementationOnce(async () => respond(500, {}))

    await vi.advanceTimersByTimeAsync(30_000)

    expect(wrapper.get('[role="alert"]').text()).toContain(
      'konnte nicht geladen werden'
    )
    expect(wrapper.text()).toContain('Beispiel, Erika')
  })

  it('wechselt nach Mitternacht beim nächsten Abruf auf den neuen Tag', async () => {
    await mountView()

    vi.setSystemTime(new Date('2026-09-14T22:00:30Z'))
    await vi.advanceTimersByTimeAsync(30_000)

    expect(attendanceCalls(fetchMock).at(-1)).toBe(
      '/api/classes/2/attendance?date=2026-09-15'
    )
  })

  it('bleibt nach Mitternacht bei gescheitertem Abruf beim Vortag mit Änderungsformular', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementationOnce(async () => respond(500, {}))

    vi.setSystemTime(new Date('2026-09-14T22:00:30Z'))
    await vi.advanceTimersByTimeAsync(30_000)

    expect(wrapper.find('form.correction').exists()).toBe(true)
    expect(wrapper.get('[role="alert"]').text()).toContain(
      'konnte nicht geladen werden'
    )
  })

  it('zeigt während des Ladens keinen Hinweis auf fehlenden Tagesstatus', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url === '/api/classes'
        ? respond(200, classes)
        : url === '/api/attendance/current-class'
          ? respond(200, { classId: 2, serverTime: '2026-09-14T06:00:00Z' })
          : new Promise<Response>(() => {})
    )
    const wrapper = await mountView()

    expect(wrapper.text()).not.toContain('kein Tagesstatus')
  })

  it('zeigt eine abgelehnte Statusänderung verständlich an', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementationOnce(async () =>
      respond(409, { code: 'ATTENDANCE_DAY_LOCKED_OR_UNPLANNED' })
    )

    await wrapper.find('form.correction').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(
      'Für diesen Tag ist keine Änderung möglich'
    )
    expect(attendanceCalls(fetchMock)).toHaveLength(1)
  })
})
