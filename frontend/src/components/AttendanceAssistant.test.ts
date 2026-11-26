import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { routeLocationKey } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AttendanceAssistant from './AttendanceAssistant.vue'
import AssistantCommandDraft from './AssistantCommandDraft.vue'

describe('Anwesenheitsassistent', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('erstellt aus der Chatantwort eine Vorschau und bucht erst nach der Bestätigung', async () => {
    const root = '/api/assistant'
    const impact = {
      unexcusedRemoved: 25,
      excusedAdded: 25,
      days: [
        {
          date: '2025-09-14',
          pending: false,
          before: null,
          after: null,
          unexcusedRemoved: 25,
          excusedAdded: 25
        }
      ]
    }
    const confirmation = root + '/excusals/demo-preview/confirm'
    const responses: Record<string, unknown> = {
      [root]: { demoEnabled: true },
      [root + '/messages']: {
        kind: 'excusal',
        answer: 'Prüfe den Entwurf.',
        route: null,
        studentName: 'Robin Beispiel',
        classCode: 'A1',
        date: '2025-09-14',
        startsAt: '08:15',
        endsAt: '09:00'
      },
      '/api/classes': [{ id: 1, classCode: 'A1' }],
      '/api/classes/1/students': [
        {
          id: 3,
          firstName: 'Robin',
          lastName: 'Beispiel',
          birthDate: '2009-01-01'
        }
      ],
      [root + '/excusals/preview']: { id: 'demo-preview', impact },
      [confirmation]: impact
    }
    const fetchMock = vi.fn<
      (url: string, options: RequestInit) => Promise<Response>
    >(async (url) => Response.json(responses[url]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AttendanceAssistant, {
      global: { plugins: [createPinia(), PrimeVue], stubs: ['RouterLink'] }
    })

    try {
      await wrapper
        .get('button[aria-label="Klassenlotse öffnen"]')
        .trigger('click')
      await flushPromises()
      await wrapper
        .get('textarea')
        .setValue('Entschuldige Robin in A1 von 08:15 bis 09:00.')
      const input = wrapper.get('textarea')
      await input.trigger('keydown', { key: 'Enter', shiftKey: true })
      await input.trigger('keydown', { key: 'Enter', isComposing: true })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      expect(wrapper.text()).toContain('Prüfe den Entwurf.')
      expect(fetchMock.mock.calls.map(([url]) => url)).not.toContain(
        confirmation
      )
      expect(wrapper.text()).not.toContain('Entschuldigung buchen')

      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.text()).toContain('−25 Min.')
      expect(JSON.parse(String(fetchMock.mock.lastCall?.[1].body))).toEqual({
        classId: 1,
        studentId: 3,
        date: '2025-09-14',
        startsAt: '08:15',
        endsAt: '09:00',
        status: 'ENTSCHULDIGT'
      })
      expect(fetchMock.mock.calls.map(([url]) => url)).not.toContain(
        confirmation
      )

      const confirm = wrapper
        .findAll('button')
        .find((button) => button.text() === 'Entschuldigung buchen')
      await confirm?.trigger('click')
      await flushPromises()
      expect(fetchMock.mock.lastCall?.[0]).toBe(confirmation)
      expect(wrapper.text()).toContain('Entschuldigung gebucht.')
    } finally {
      wrapper.unmount()
    }
  })
  it('zeigt beim Löschen erst den konkreten Schüler an und sendet DELETE nur nach Bestätigung', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      switch (url) {
        case '/api/assistant':
          return Response.json({ demoEnabled: true })
        case '/api/assistant/messages':
          return Response.json({
            kind: 'command',
            answer: 'Ich bereite das Löschen vor.',
            command: {
              key: 'delete_student',
              fields: { classCode: 'FI26A', studentName: 'Robin Beispiel' }
            }
          })
        case '/api/staff-id':
          return Response.json(4)
        case '/api/classes':
          return Response.json([
            { id: 2, classCode: 'FI26A', classTeacherId: 4 }
          ])
        case '/api/students':
          return Response.json([
            {
              id: 7,
              firstName: 'Robin',
              lastName: 'Beispiel',
              classId: 2,
              birthDate: '2010-02-08'
            }
          ])
        case '/api/students/7':
          if (init?.method === 'DELETE')
            return new Response(null, { status: 204 })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AttendanceAssistant, {
      global: {
        plugins: [createPinia(), PrimeVue],
        provide: { [routeLocationKey as symbol]: { query: {} } },
        stubs: ['RouterLink']
      }
    })
    try {
      await wrapper
        .get('button[aria-label="Klassenlotse öffnen"]')
        .trigger('click')
      await flushPromises()
      const input = wrapper.get('textarea')
      await input.setValue('Lösche Robin Beispiel aus FI26A.')
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()

      const sentDelete = () =>
        fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')
      expect(sentDelete()).toBe(false)
      await wrapper.get('.command form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('.command__preview').text()).toContain(
        'Beispiel, Robin'
      )
      expect(sentDelete()).toBe(false)
      await wrapper
        .findAll('.command button')
        .find((button) => button.text() === 'Löschen bestätigen')!
        .trigger('click')
      await flushPromises()
      expect(
        fetchMock.mock.calls
          .filter(([, init]) => init?.method === 'DELETE')
          .map(([url]) => url)
      ).toEqual(['/api/students/7'])
      expect(wrapper.text()).toContain('Schüler gelöscht.')
    } finally {
      wrapper.unmount()
    }
  })

  it('behält die Anfrage-ID für dieselben Fehlminuten nach einem Verbindungsabbruch', async () => {
    const bookings: RequestInit['body'][] = []
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/classes')
        return Response.json([{ id: 2, classCode: 'FI26A' }])
      if (url === '/api/students')
        return Response.json([
          {
            id: 7,
            firstName: 'Robin',
            lastName: 'Beispiel',
            classId: 2,
            birthDate: '2010-02-08'
          }
        ])
      if (
        url === '/api/students/7/attendance/2025-09-15/manual-absence-minutes'
      ) {
        bookings.push(init?.body)
        if (bookings.length === 1) throw new TypeError('Verbindungsabbruch')
        return Response.json({ changed: false })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AssistantCommandDraft, {
      props: {
        hint: {
          key: 'add_absence_minutes',
          fields: {
            classCode: 'FI26A',
            studentName: 'Robin Beispiel',
            date: '2025-09-15',
            minutes: '45'
          }
        }
      },
      global: { plugins: [createPinia(), PrimeVue] }
    })

    try {
      const click = (label: string) =>
        wrapper
          .findAll('button')
          .find((button) => button.text() === label)!
          .trigger('click')
      await flushPromises()
      await wrapper.get('form').trigger('submit')
      await click('Änderung bestätigen')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain(
        'Das Ergebnis konnte nicht abgerufen werden.'
      )

      await wrapper.get('form').trigger('submit')
      await click('Änderung bestätigen')
      await flushPromises()

      expect(bookings).toHaveLength(2)
      expect(JSON.parse(String(bookings[0]))).toEqual({
        minutes: 45,
        requestId: expect.any(String)
      })
      expect(bookings[1]).toEqual(bookings[0])
      expect(wrapper.emitted('saved')).toHaveLength(1)
    } finally {
      wrapper.unmount()
    }
  })

  it('legt bei einer Stunde zuerst alte und neue Zeiten vor und übernimmt die Änderung nach Freigabe', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/assistant') return Response.json({ demoEnabled: true })
      if (url === '/api/assistant/messages')
        return Response.json({
          kind: 'command',
          answer: 'Bitte prüfe die Änderung.',
          command: {
            key: 'update_slot',
            fields: {
              timetable: 'Plan A',
              slot: 'Montag 07:30',
              startsAt: '08:00'
            }
          }
        })
      if (url === '/api/planning')
        return Response.json({
          timetables: [
            {
              id: 5,
              name: 'Plan A',
              slots: [
                {
                  id: 11,
                  weekday: 'MONTAG',
                  startsAt: '07:30:00',
                  endsAt: '09:00:00',
                  roomId: 3,
                  roomNumber: 'H01'
                }
              ]
            }
          ],
          rooms: [{ id: 3, roomNumber: 'H01' }],
          blockPlans: [],
          classes: []
        })
      if (
        url === '/api/planning/timetables/5/slots/11' &&
        init?.method === 'PUT'
      ) {
        return Response.json({ effectiveOn: '2026-11-30' })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = { name: 'Test', role: 'admin' }
    const wrapper = mount(AttendanceAssistant, {
      global: {
        plugins: [pinia, PrimeVue],
        provide: { [routeLocationKey as symbol]: { query: {} } },
        stubs: ['RouterLink']
      }
    })
    try {
      await wrapper
        .get('button[aria-label="Klassenlotse öffnen"]')
        .trigger('click')
      await flushPromises()
      const input = wrapper.get('textarea')
      await input.setValue('Verschiebe Montag 07:30 im Plan A auf 08:00.')
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      await wrapper.get('.command form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('.command__preview').text()).toContain('07:30')
      expect(wrapper.get('.command__preview').text()).toContain('08:00')
      expect(
        fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')
      ).toBe(false)
      await wrapper
        .findAll('.command button')
        .find((button) => button.text() === 'Änderung bestätigen')!
        .trigger('click')
      await flushPromises()
      const write = fetchMock.mock.calls.find(
        ([, init]) => init?.method === 'PUT'
      )
      expect(write?.[0]).toBe('/api/planning/timetables/5/slots/11')
      expect(JSON.parse(String(write?.[1]?.body))).toEqual({
        weekday: 'MONTAG',
        startsAt: '08:00',
        endsAt: '09:00:00',
        roomId: 3
      })
      expect(wrapper.text()).toContain('30.11.2026')
    } finally {
      wrapper.unmount()
    }
  })
})
