import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import router from './router'
import { useAuthStore } from '@/stores/auth'
import { getJson, postJson } from '@/api/client'

afterEach(() => vi.unstubAllGlobals())

describe('Router-Guard für administrative Routen', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it.each(['raeume', 'terminals', 'personal'])(
    'sperrt %s für Lehrkräfte auch beim Direktaufruf',
    async (name) => {
      const auth = useAuthStore()
      auth.user = { name: 'L. Kraft', role: 'lehrkraft' }
      auth.sessionChecked = true

      await router.push({ name })

      expect(router.currentRoute.value.name).toBe('live-anwesenheit')
    }
  )

  it.each(['raeume', 'terminals', 'personal'])(
    'erlaubt Administratoren den Direktaufruf von %s',
    async (name) => {
      const auth = useAuthStore()
      auth.user = { name: 'A. Muster', role: 'admin' }
      auth.sessionChecked = true

      await router.push({ name })

      expect(router.currentRoute.value.name).toBe(name)
    }
  )
})

describe('HTTP 401 während einer laufenden Sitzung', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = { name: 'L. Kraft', role: 'lehrkraft' }
    auth.sessionChecked = true
    await router.push('/auswertungen?datum=2026-10-05')
  })

  it.each(['GET', 'POST'])(
    'leitet nach einem %s mit HTTP 401 sofort zur Anmeldung',
    async (method) => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
      )

      const response =
        method === 'GET'
          ? await getJson('/api/attendance')
          : await postJson('/api/attendance', {})

      expect(response.status).toBe(401)
      expect(useAuthStore().isLoggedIn).toBe(false)
      expect(router.currentRoute.value.name).toBe('login')
      expect(router.currentRoute.value.query.weiter).toBe(
        '/auswertungen?datum=2026-10-05'
      )
      await router.push('/klassen')
      expect(router.currentRoute.value.name).toBe('login')
    }
  )

  it.each([403, 500])('erhält die Sitzung bei HTTP %s', async (status) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status }))
    )

    await getJson('/api/attendance')

    expect(useAuthStore().isLoggedIn).toBe(true)
    expect(router.currentRoute.value.name).toBe('auswertungen')
  })

  it('behandelt HTTP 401 beim Login ausschließlich als Anmeldefehler', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    )

    expect(await useAuthStore().login('test', 'wrong-test-password')).toBe(
      false
    )

    expect(useAuthStore().isLoggedIn).toBe(true)
    expect(router.currentRoute.value.name).toBe('auswertungen')
    expect(useAuthStore().errorMessage).toContain('Anmeldung fehlgeschlagen')
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

  it('stellt keine Vue-Terminalroute bereit', () => {
    expect(router.resolve('/terminal/test').matched).toEqual([])
  })
})
