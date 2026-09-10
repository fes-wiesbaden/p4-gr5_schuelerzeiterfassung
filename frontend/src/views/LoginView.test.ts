import { describe, expect, it, beforeEach, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'

import LoginView from './LoginView.vue'
import { useAuthStore } from '@/stores/auth'

const push = vi.fn()
const replace = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push, replace }),
  useRoute: () => ({ query: {} })
}))

function baueAnsicht() {
  return mount(LoginView, {
    global: {
      plugins: [PrimeVue],
      stubs: { RouterLink: true }
    }
  })
}

describe('Anmeldemaske', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    replace.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('beschriftet beide Felder und verknüpft sie mit der Eingabe', () => {
    const ansicht = baueAnsicht()

    const labels = ansicht.findAll('label')
    expect(labels.map((l) => l.text())).toEqual(['Benutzername', 'Passwort'])
    expect(ansicht.find('#login-username').exists()).toBe(true)
    expect(ansicht.find('#login-password').exists()).toBe(true)
  })

  it('verwendet ein Passwortfeld, damit die Eingabe nicht lesbar ist', () => {
    const ansicht = baueAnsicht()

    expect(ansicht.find('#login-password').attributes('type')).toBe('password')
  })

  it('lässt sich per Enter absenden, weil es ein echtes Formular ist', async () => {
    const auth = useAuthStore()
    const login = vi.spyOn(auth, 'login').mockResolvedValue(true)
    const ansicht = baueAnsicht()

    await ansicht.find('form').trigger('submit')

    expect(login).toHaveBeenCalled()
  })

  it('zeigt die Fehlermeldung des Stores als Hinweis an', async () => {
    const auth = useAuthStore()
    auth.errorMessage = 'Anmeldung fehlgeschlagen. Bitte Eingaben pruefen.'
    const ansicht = baueAnsicht()
    await ansicht.vm.$nextTick()

    const hinweis = ansicht.find('[role="alert"]')
    expect(hinweis.exists()).toBe(true)
    expect(hinweis.text()).toContain('Anmeldung fehlgeschlagen')
  })

  it('leert das Passwortfeld nach einem Fehlversuch', async () => {
    const auth = useAuthStore()
    vi.spyOn(auth, 'login').mockResolvedValue(false)
    const ansicht = baueAnsicht()

    const feld = ansicht.find('#login-password')
    await feld.setValue('falsch')
    await ansicht.find('form').trigger('submit')
    await ansicht.vm.$nextTick()

    expect((feld.element as HTMLInputElement).value).toBe('')
  })

  it('führt Administratoren nach der Anmeldung in die Verwaltung', async () => {
    const auth = useAuthStore()
    vi.spyOn(auth, 'login').mockImplementation(async () => {
      auth.user = { name: 'A. Muster', role: 'admin' }
      return true
    })
    const ansicht = baueAnsicht()

    await ansicht.find('form').trigger('submit')
    await ansicht.vm.$nextTick()

    expect(replace).toHaveBeenCalledWith({ name: 'raeume' })
  })

  it('führt Lehrkräfte nach der Anmeldung zur Live-Anwesenheit', async () => {
    const auth = useAuthStore()
    vi.spyOn(auth, 'login').mockImplementation(async () => {
      auth.user = { name: 'L. Kraft', role: 'lehrkraft' }
      return true
    })
    const ansicht = baueAnsicht()

    await ansicht.find('form').trigger('submit')
    await ansicht.vm.$nextTick()

    expect(replace).toHaveBeenCalledWith({ name: 'live-anwesenheit' })
  })
})
