import { mount } from '@vue/test-utils'
import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'

import AppLayout from './AppLayout.vue'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

describe('AppLayout Navigation', () => {
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
  })

  it('blendet den Administration-Bereich für Lehrkräfte aus', () => {
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, PrimeVue] }
    })

    expect(wrapper.text()).not.toContain('Administration')
  })

  it('zeigt die Hauptnavigation unabhängig von der Rolle', () => {
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }

    const wrapper = mount(AppLayout, {
      global: { plugins: [router, PrimeVue] }
    })

    expect(wrapper.text()).toContain('Live-Anwesenheit')
    expect(wrapper.text()).toContain('Klassen')
  })
})
