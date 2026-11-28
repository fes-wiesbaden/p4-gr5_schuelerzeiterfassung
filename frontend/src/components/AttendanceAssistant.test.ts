import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { routeLocationKey, routerKey } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import PrimeVue from 'primevue/config'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { timetableDraft } from '@/api/timetableImport'

import AttendanceAssistant from './AttendanceAssistant.vue'
import AssistantCommandDraft from './AssistantCommandDraft.vue'
import ExcusalDraft from './ExcusalDraft.vue'

describe('Anwesenheitsassistent', () => {
  afterEach(() => {
    timetableDraft.value = undefined
  })

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
            minutes: '45',
            note: 'Nach der 4. Stunde gegangen'
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
        note: 'Nach der 4. Stunde gegangen',
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
        endsAt: '09:00',
        roomId: 3
      })
      expect(wrapper.text()).toContain('30.11.2026')
    } finally {
      wrapper.unmount()
    }
  })

  function mountChat(
    reply: (
      body: { messages: { content: string }[] },
      init?: RequestInit
    ) => Promise<Response>,
    options: { admin?: boolean; other?: (url: string) => Response } = {}
  ) {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/assistant') return Response.json({ demoEnabled: true })
      if (url === '/api/assistant/messages')
        return reply(JSON.parse(String(init?.body)), init)
      return options.other?.(url) ?? new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const pinia = createPinia()
    setActivePinia(pinia)
    if (options.admin) useAuthStore().user = { name: 'Test', role: 'admin' }
    const push = vi.fn()
    const wrapper = mount(AttendanceAssistant, {
      attachTo: document.body,
      global: {
        plugins: [pinia, PrimeVue],
        provide: {
          [routeLocationKey as symbol]: { query: {} },
          [routerKey as symbol]: { push }
        },
        stubs: ['RouterLink']
      }
    })
    return { wrapper, fetchMock, push }
  }

  async function openChat(wrapper: ReturnType<typeof mount>) {
    await wrapper
      .get('button[aria-label="Klassenlotse öffnen"]')
      .trigger('click')
    await flushPromises()
  }

  it('setzt den Fokus nach einer Antwort auf einen Vorschlag ins Eingabefeld und sperrt das Scrollen der Seite', async () => {
    const { wrapper } = mountChat(async () =>
      Response.json({ kind: 'answer', answer: 'Heute fehlt niemand.' })
    )
    try {
      await wrapper
        .get('button[aria-label="Klassenlotse öffnen"]')
        .trigger('click')
      await flushPromises()
      expect(document.documentElement.style.overflow).toBe('hidden')

      await wrapper.get('.assistant__suggestion').trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('Heute fehlt niemand.')
      const field = wrapper.get('textarea').element
      expect(field.disabled).toBe(false)
      expect(field.readOnly).toBe(false)
      expect(document.activeElement).toBe(field)

      await wrapper
        .get('button[aria-label="Klassenlotse schließen"]')
        .trigger('click')
      await flushPromises()
      expect(document.documentElement.style.overflow).toBe('')
    } finally {
      wrapper.unmount()
    }
  })

  it('ersetzt eine leere Antwort durch einen Standardtext und sendet keine leeren Einträge', async () => {
    const sent: { content: string }[][] = []
    const { wrapper } = mountChat(async (body) => {
      sent.push(body.messages)
      return Response.json({ kind: 'answer', answer: '  ' })
    })
    try {
      await wrapper
        .get('button[aria-label="Klassenlotse öffnen"]')
        .trigger('click')
      await flushPromises()
      const input = wrapper.get('textarea')
      await input.setValue('Erste Frage')
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      const bubbles = wrapper.findAll('.assistant__message p')
      expect(bubbles).toHaveLength(2)
      expect(bubbles[1]!.text()).toContain(
        'Darauf habe ich gerade keine Antwort.'
      )

      await input.setValue('Zweite Frage')
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      expect(sent).toHaveLength(2)
      expect(sent[1]!.map(({ content }) => content)).toEqual([
        'Erste Frage',
        expect.stringContaining('Darauf habe ich gerade keine Antwort.'),
        'Zweite Frage'
      ])
      expect(sent.flat().every(({ content }) => content.trim())).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('bricht eine laufende Frage ab und behält den Eingabetext', async () => {
    // Wie fetch endet die Anfrage erst durch das Abbruchsignal
    const { wrapper } = mountChat(
      (_, init) =>
        new Promise<Response>((_resolve, reject) =>
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('Abgebrochen', 'AbortError'))
          )
        )
    )
    try {
      await wrapper
        .get('button[aria-label="Klassenlotse öffnen"]')
        .trigger('click')
      await flushPromises()
      const input = wrapper.get('textarea')
      await input.setValue('Wer fehlt heute?')
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      expect(wrapper.text()).toContain('Klassenlotse antwortet')

      await wrapper
        .findAll('.assistant__status button')
        .find((button) => button.text() === 'Abbrechen')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.text()).not.toContain('Klassenlotse antwortet')
      expect(wrapper.find('.assistant__message').exists()).toBe(false)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(input.element.value).toBe('Wer fehlt heute?')
      expect(document.activeElement).toBe(input.element)
    } finally {
      wrapper.unmount()
    }
  })

  it('nennt beim Klick auf die Vorschau den Grund, wenn das Ende vor dem Beginn liegt', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
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
        return new Response(null, { status: 404 })
      })
    )
    const wrapper = mount(AssistantCommandDraft, {
      props: {
        hint: {
          key: 'correct_period',
          fields: {
            classCode: 'FI26A',
            studentName: 'Robin Beispiel',
            date: '2025-09-15',
            status: 'Entschuldigt',
            startsAt: '10:00:00',
            endsAt: '09:00'
          }
        }
      },
      global: { plugins: [createPinia(), PrimeVue] }
    })
    try {
      await flushPromises()
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain(
        'Das Ende muss nach dem Beginn liegen.'
      )
      expect(wrapper.find('.command__preview').exists()).toBe(false)

      const times = wrapper.findAll<HTMLInputElement>('input.german-time')
      expect(times[0]!.element.value).toBe('10:00')
      await times[1]!.setValue('8:00')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain(
        'Gib die Uhrzeit im Format HH:MM ein'
      )

      await times[1]!.setValue('11:00')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('.command__preview').text()).toContain('10:00 Uhr')
      expect(wrapper.get('.command__preview').text()).not.toContain('10:00:00')
    } finally {
      wrapper.unmount()
    }
  })

  it('erklärt den fehlenden Attest-Haken und führt unveränderte Tage in der Vorschau auf', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/classes')
        return Response.json([{ id: 1, classCode: 'A1' }])
      if (url === '/api/classes/1/students')
        return Response.json([
          {
            id: 3,
            firstName: 'Robin',
            lastName: 'Beispiel',
            birthDate: '2009-01-01'
          }
        ])
      if (url === '/api/assistant/excusals/preview')
        return Response.json({
          id: 'demo',
          impact: {
            unexcusedRemoved: 0,
            excusedAdded: 0,
            days: [
              {
                date: '2025-09-15',
                pending: false,
                before: 'ABWESEND',
                after: 'ENTSCHULDIGT',
                unexcusedRemoved: 0,
                excusedAdded: 0
              }
            ]
          }
        })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ExcusalDraft, {
      props: {
        hint: {
          classCode: 'A1',
          studentName: 'Robin Beispiel',
          date: '2025-09-15',
          endDate: '2025-09-17',
          scope: 'range'
        }
      },
      global: { plugins: [createPinia(), PrimeVue] }
    })
    try {
      await flushPromises()
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain(
        'Setze den Haken bei „Ein Attest liegt vor“'
      )
      expect(
        fetchMock.mock.calls.some(([url]) => url.endsWith('/preview'))
      ).toBe(false)

      await wrapper.get('input[type="checkbox"]').setValue(true)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      const days = wrapper.findAll('.excusal__days li').map((li) => li.text())
      expect(days).toHaveLength(3)
      expect(days[0]).toContain('1 entschuldigter Fehltag')
      expect(days[1]).toContain('16.09.2025')
      expect(days[1]).toContain('keine Änderung')
    } finally {
      wrapper.unmount()
    }
  })
  it('sendet keine erkennbaren Passwörter oder Kartenkennungen an das Modell', async () => {
    const { wrapper, fetchMock } = mountChat(async () =>
      Response.json({ kind: 'answer', answer: 'Unter Personal.' })
    )
    const sentMessages = () =>
      fetchMock.mock.calls.filter(([url]) => url === '/api/assistant/messages')
    try {
      await openChat(wrapper)
      const input = wrapper.get('textarea')
      for (const secret of [
        'Lege Lehrkraft Kraft an, Passwort auf Start1234!',
        'Das Passwort von Frau Beispiel lautet Sommer24',
        'Ordne Robin die Karte 04A1B2C3 zu'
      ]) {
        await input.setValue(secret)
        await input.trigger('keydown', { key: 'Enter' })
        await flushPromises()
        expect(wrapper.get('[role="alert"]').text()).toContain(
          'Gib Passwörter oder Kartenkennungen im Formular'
        )
      }
      expect(sentMessages()).toHaveLength(0)

      await input.setValue('Wie ändere ich das Passwort einer Lehrkraft?')
      await input.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      expect(sentMessages()).toHaveLength(1)
    } finally {
      wrapper.unmount()
    }
  })

  it('liest ein Stundenplanbild, klärt die Klasse und öffnet erst dann den Entwurf', async () => {
    const draft = {
      name: 'Plan aus Bild',
      slots: [
        {
          weekday: 'MONTAG',
          startsAt: '07:30',
          endsAt: '09:00',
          subject: 'LF',
          teacherName: null,
          roomNumber: null,
          teacherId: null,
          roomId: null
        }
      ],
      notes: [],
      rooms: [],
      teachers: [],
      classCode: null,
      classId: null,
      classes: [],
      blockPlans: []
    }
    const { wrapper, fetchMock, push } = mountChat(
      async () => new Response(null, { status: 404 }),
      {
        admin: true,
        other: (url) =>
          url === '/api/assistant/timetable-drafts'
            ? Response.json(draft)
            : new Response(null, { status: 404 })
      }
    )
    const upload = async () => {
      const field = wrapper.get('input[type="file"]')
      Object.defineProperty(field.element, 'files', {
        configurable: true,
        value: [new File(['x'], 'plan.png', { type: 'image/png' })]
      })
      await field.trigger('change')
      await flushPromises()
    }
    const uploads = () =>
      fetchMock.mock.calls.filter(
        ([url]) => url === '/api/assistant/timetable-drafts'
      )
    try {
      await openChat(wrapper)
      await upload()
      expect(uploads()).toHaveLength(1)
      expect(wrapper.text()).toContain('Ich habe 1 Unterrichtszeit erkannt.')
      expect(wrapper.get('.class-question h3').text()).toContain(
        'Ich konnte keine Klasse erkennen.'
      )
      expect(timetableDraft.value).toBeUndefined()

      await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Keiner Klasse zuordnen')!
        .trigger('click')
      await flushPromises()
      expect(timetableDraft.value?.target).toEqual({ mode: 'none' })
      expect(push).toHaveBeenCalledWith({ name: 'stundenplan-entwurf' })

      // Ein weiterer Upload ersetzt den offenen Entwurf nur nach Rückfrage
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
      await openChat(wrapper)
      await upload()
      expect(confirm).toHaveBeenCalledTimes(1)
      expect(uploads()).toHaveLength(1)
    } finally {
      wrapper.unmount()
    }
  })
})
