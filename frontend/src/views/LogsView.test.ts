import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LogsView from './LogsView.vue'
import { useAuthStore, type Role } from '@/stores/auth'

const scans = {
  items: [
    {
      scanId: 'a',
      scannedAt: '2026-09-14T06:30:00Z',
      rfidUid: 'UNBEKANNT-99',
      student: null,
      classCode: null
    }
  ],
  total: 1
}

const audits = {
  items: [
    {
      id: 4,
      occurredAt: '2026-09-14T07:00:00Z',
      eventType: 'STATUS_CHANGE',
      student: 'Beispiel, Erika',
      classCode: 'KL-A',
      actor: 'Kraft, Lena (l.kraft)',
      oldStatus: 'ANWESEND',
      newStatus: 'ENTSCHULDIGT',
      unexcusedMinutesDelta: -60,
      excusedMinutesDelta: 60
    }
  ],
  total: 1
}

describe('LogsView', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers({ toFake: ['Date'] })
    // 08:00 Berliner Sommerzeit
    vi.setSystemTime(new Date('2026-09-14T06:00:00Z'))
    vi.stubGlobal('fetch', fetchMock)
    // jsdom kennt weder ResizeObserver (Reiterleiste) noch matchMedia (Auswahlfeld)
    vi.stubGlobal('matchMedia', () => ({
      matches: false,
      addEventListener() {},
      removeEventListener() {}
    }))
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      }
    )
    fetchMock.mockImplementation(
      async (url: string) =>
        new Response(JSON.stringify(url.includes('/audits') ? audits : scans), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
    )
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  async function mountAs(role: Role) {
    useAuthStore().user = { name: 'Test', role }
    const wrapper = mount(LogsView, { global: { plugins: [PrimeVue] } })
    await flushPromises()
    return wrapper
  }

  function urls() {
    return fetchMock.mock.calls.map(([url]) => new URL(url, 'http://x'))
  }

  it('zeigt Lehrkräften nur die RawScans der letzten sieben Berliner Tage', async () => {
    const wrapper = await mountAs('lehrkraft')

    expect(wrapper.text()).toContain('RawScans')
    expect(wrapper.text()).not.toContain('Audit-Logs')
    expect(wrapper.text()).toContain('Unbekannte Karte')
    expect(urls().map((url) => url.pathname)).toEqual(['/api/logs/raw-scans'])
    const params = urls()[0].searchParams
    expect(params.get('from')).toBe('2026-09-07T22:00:00.000Z')
    expect(params.get('to')).toBe('2026-09-14T22:00:00.000Z')
    expect(params.get('sort')).toBe('scannedAt')
    expect(params.get('direction')).toBe('desc')
    wrapper.unmount()
  })

  it('lädt die Audit-Logs erst beim Öffnen ihres Reiters', async () => {
    const wrapper = await mountAs('admin')
    expect(urls().map((url) => url.pathname)).toEqual(['/api/logs/raw-scans'])

    const tab = wrapper
      .findAll('[role="tab"]')
      .find((element) => element.text() === 'Audit-Logs')!
    await tab.trigger('click')
    await flushPromises()
    expect(urls().map((url) => url.pathname)).toEqual([
      '/api/logs/raw-scans',
      '/api/logs/audits'
    ])
    expect(wrapper.text()).toContain('Kraft, Lena (l.kraft)')
    expect(wrapper.text()).toContain('unentschuldigt -60, entschuldigt +60')
    wrapper.unmount()
  })

  it('sendet Suche und Sortierung an das Backend und beginnt wieder bei Seite 1', async () => {
    const wrapper = await mountAs('lehrkraft')
    await wrapper.find('.log-filter__search input').setValue(' Erika ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(urls().at(-1)!.searchParams.get('search')).toBe('Erika')

    const header = wrapper
      .findAll('th')
      .find((element) => element.text().includes('RFID-UID'))!
    await header.trigger('click')
    await flushPromises()
    const params = urls().at(-1)!.searchParams
    expect(params.get('sort')).toBe('rfidUid')
    expect(params.get('page')).toBe('0')
    expect(params.get('search')).toBe('Erika')
    wrapper.unmount()
  })

  it('sortiert mit dem abgeschickten Filter, nicht mit einer halb getippten Suche', async () => {
    const wrapper = await mountAs('lehrkraft')
    await wrapper
      .find('.log-filter__search input')
      .setValue('noch nicht gesendet')

    const header = wrapper
      .findAll('th')
      .find((element) => element.text().includes('RFID-UID'))!
    await header.trigger('click')
    await flushPromises()
    expect(urls().at(-1)!.searchParams.has('search')).toBe(false)
    wrapper.unmount()
  })

  it('filtert nach Uhrzeit, Bis gilt einschließlich der Minute', async () => {
    const wrapper = await mountAs('lehrkraft')
    const [from, to] = wrapper.findAll('input[type="datetime-local"]')
    await from.setValue('2026-09-14T08:00')
    await to.setValue('2026-09-14T09:30')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const params = urls().at(-1)!.searchParams
    expect(params.get('from')).toBe('2026-09-14T06:00:00.000Z')
    expect(params.get('to')).toBe('2026-09-14T07:31:00.000Z')
    wrapper.unmount()
  })

  it('behält die Meldung zum ungültigen Zeitraum beim Sortieren', async () => {
    const wrapper = await mountAs('lehrkraft')
    const [from, to] = wrapper.findAll('input[type="datetime-local"]')
    await from.setValue('2026-09-14T10:00')
    await to.setValue('2026-09-14T09:00')
    await wrapper.find('form').trigger('submit')
    const header = wrapper
      .findAll('th')
      .find((element) => element.text().includes('RFID-UID'))!
    await header.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('gültigen Zeitraum')
    expect(urls().at(-1)!.searchParams.get('from')).toBe(
      '2026-09-07T22:00:00.000Z'
    )
    wrapper.unmount()
  })

  it('zeigt nur die Antwort der neuesten Anfrage', async () => {
    const wrapper = await mountAs('lehrkraft')
    let answerSlowRequest: (response: Response) => void = () => {}
    fetchMock.mockImplementationOnce(
      () => new Promise<Response>((resolve) => (answerSlowRequest = resolve))
    )
    const header = wrapper
      .findAll('th')
      .find((element) => element.text().includes('RFID-UID'))!
    await header.trigger('click')
    await header.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Unbekannte Karte')

    answerSlowRequest(
      new Response(JSON.stringify({ items: [], total: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    )
    await flushPromises()
    expect(wrapper.text()).toContain('Unbekannte Karte')
    wrapper.unmount()
  })
})
