import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'

import AppLayout from './AppLayout.vue'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'

describe('AppLayout Navigation', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  })
  beforeEach(async () => {
    setActivePinia(createPinia())

    // Simuliert eine bereits geprüfte Anmeldung, damit der Test die Navigation
    // ohne Backend-Anfrage oder Weiterleitung zur Anmeldeseite aufbauen kann
    const auth = useAuthStore()
    auth.user = { name: 'A. Muster', role: 'admin' }
    auth.sessionChecked = true

    await router.push('/')
    await router.isReady()
  })

  it('zeigt den Administration-Bereich für Administratoren', () => {
    const auth = useAuthStore()
    auth.user = { name: 'A. Muster', role: 'admin' }

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, PrimeVue] }
    })

    expect(wrapper.text()).toContain('Administration')
    expect(wrapper.text()).toContain('Räume')
    expect(wrapper.find('a[href="/personal"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('blendet den Administration-Bereich für Lehrkräfte aus', () => {
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, PrimeVue] }
    })

    expect(wrapper.text()).not.toContain('Administration')
    expect(wrapper.find('a[href="/personal"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('zeigt die Hauptnavigation unabhängig von der Rolle', () => {
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, PrimeVue] }
    })

    expect(wrapper.text()).toContain('Live-Anwesenheit')
    expect(wrapper.text()).toContain('Klassen')
    expect(wrapper.get('a[href="/schueler"]').text()).toBe('Schüler')
    expect(wrapper.find('a[href="/historie"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([
    ['/klassen?seite=2', '/klassen?seite=2'],
    ['/unbekannt', '/live-anwesenheit'],
    ['/personal', '/live-anwesenheit']
  ])(
    'holt das CSRF-Cookie nach und leitet von %s zu %s weiter',
    async (weiter, target) => {
      const auth = useAuthStore()
      auth.user = null
      auth.sessionChecked = false
      const fetchMock = vi
        .fn()
        .mockRejectedValueOnce(new Error('offline'))
        .mockImplementationOnce(() => {
          document.cookie = 'XSRF-TOKEN=csrf-test'
          return new Response(null, { status: 401 })
        })
        .mockResolvedValueOnce(
          Response.json({ name: 'L. Kraft', role: 'lehrkraft' })
        )
      vi.stubGlobal('fetch', fetchMock)

      await router.push({ name: 'login', query: { weiter } })
      const wrapper = mount(LoginView, {
        global: { plugins: [router, PrimeVue] }
      })
      await wrapper.get('#login-username').setValue('lehrkraft')
      await wrapper.get('#login-password').setValue('testpasswort')
      await wrapper.get('form').trigger('submit')
      await flushPromises()

      expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
        '/api/me',
        '/api/me',
        '/api/login'
      ])
      expect(fetchMock.mock.calls[2][1].headers['X-XSRF-TOKEN']).toBe(
        'csrf-test'
      )
      expect(router.currentRoute.value.fullPath).toBe(target)
      wrapper.unmount()
    }
  )

  it.each([403, 500, 'offline'])(
    'zeigt einen Logout-Fehler (%s) und erlaubt einen neuen Versuch',
    async (status) => {
      const fetchMock = vi.fn()
      if (status === 'offline') {
        fetchMock.mockRejectedValueOnce(new Error('offline'))
      } else {
        fetchMock.mockResolvedValueOnce(
          new Response(null, { status: status as number })
        )
      }
      fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
      fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(AppLayout, {
        global: { plugins: [router, PrimeVue] }
      })
      const logout = wrapper
        .findAll('button')
        .find((button) => button.text() === 'Abmelden')!

      await logout.trigger('click')
      await flushPromises()

      expect(wrapper.get('[role="alert"]').text()).toContain('Abmeldung')
      expect(useAuthStore().isLoggedIn).toBe(true)
      expect(useAuthStore().busy).toBe(false)
      expect(useAuthStore().sessionChecked).toBe(true)
      expect(router.currentRoute.value.name).not.toBe('login')
      await logout.trigger('click')
      await flushPromises()
      expect(useAuthStore().isLoggedIn).toBe(false)
      expect(router.currentRoute.value.name).toBe('login')
      expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
        '/api/logout',
        '/api/logout',
        '/api/me'
      ])
      wrapper.unmount()
    }
  )
})
