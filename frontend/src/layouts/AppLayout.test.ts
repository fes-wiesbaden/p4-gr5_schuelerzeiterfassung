import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'

import AppLayout from './AppLayout.vue'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

describe('AppLayout Navigation', () => {
  afterEach(() => vi.unstubAllGlobals())
  beforeEach(async () => {
    setActivePinia(createPinia())

    // Ohne angemeldeten Nutzer schickt der Router auf die Anmeldeseite.
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
    wrapper.unmount()
  })

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
