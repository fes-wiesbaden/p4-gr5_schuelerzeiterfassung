import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import router from './router'
import { useAuthStore } from '@/stores/auth'

describe('Router-Guard für administrative Routen', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('leitet Lehrkräfte bei Direktaufruf einer administrativen Route auf Live-Anwesenheit um', async () => {
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }
    auth.sessionChecked = true

    await router.push({ name: 'raeume' })

    expect(router.currentRoute.value.name).toBe('live-anwesenheit')
  })

  it('lässt Administratoren administrative Routen direkt aufrufen', async () => {
    const auth = useAuthStore()
    auth.user = { name: 'A. Muster', role: 'admin' }
    auth.sessionChecked = true

    await router.push({ name: 'raeume' })

    expect(router.currentRoute.value.name).toBe('raeume')
  })
})

describe('Router-Guard für die Anmeldung', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('schickt nicht angemeldete Nutzer auf die Anmeldeseite', async () => {
    const auth = useAuthStore()
    auth.user = null
    auth.sessionChecked = true

    await router.push({ name: 'klassen' })

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('merkt sich das gewünschte Ziel für die Zeit nach der Anmeldung', async () => {
    const auth = useAuthStore()
    auth.user = null
    auth.sessionChecked = true

    await router.push({ name: 'auswertungen' })

    expect(router.currentRoute.value.query.weiter).toBe('/auswertungen')
  })

  it('lässt angemeldete Nutzer nicht auf der Anmeldeseite landen', async () => {
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }
    auth.sessionChecked = true

    await router.push({ name: 'login' })

    expect(router.currentRoute.value.name).toBe('live-anwesenheit')
  })

  it('lässt die Terminalansicht ohne Anmeldung zu', async () => {
    const auth = useAuthStore()
    auth.user = null
    auth.sessionChecked = true

    await router.push('/terminal/1')

    expect(router.currentRoute.value.path).toBe('/terminal/1')
  })
})
