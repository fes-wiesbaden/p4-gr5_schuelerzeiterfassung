import { describe, expect, it, beforeEach, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useAuthStore } from './auth'

function antwort(ok: boolean, body: unknown = {}) {
  return { ok, json: async () => body } as Response
}

describe('Auth-Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.cookie = 'XSRF-TOKEN=test-token'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('merkt sich den angemeldeten Nutzer', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(antwort(true, { name: 'A. Muster', role: 'admin' }))
    )
    const auth = useAuthStore()

    const erfolgreich = await auth.login('muster', 'geheim')

    expect(erfolgreich).toBe(true)
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.isAdmin).toBe(true)
  })

  it('nennt bei falschen Daten keinen Grund, der den Benutzernamen verrät', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(antwort(false)))
    const auth = useAuthStore()

    await auth.login('gibtsnicht', 'falsch')

    expect(auth.isLoggedIn).toBe(false)
    expect(auth.errorMessage).not.toMatch(/benutzer|user|passwort|unbekannt/i)
    expect(auth.errorMessage).toBe(
      'Anmeldung fehlgeschlagen. Bitte Eingaben pruefen.'
    )
  })

  it('meldet einen nicht erreichbaren Server getrennt von falschen Daten', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const auth = useAuthStore()

    await auth.login('muster', 'geheim')

    expect(auth.errorMessage).toMatch(/nicht erreichbar/i)
  })

  it('schickt das Passwort nur im Rumpf und niemals in der Adresse', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(antwort(true, { name: 'A', role: 'admin' }))
    vi.stubGlobal('fetch', fetchMock)
    const auth = useAuthStore()

    await auth.login('muster', 'geheim')

    const [url, optionen] = fetchMock.mock.calls[0]
    expect(url).not.toContain('geheim')
    expect(optionen.body).toContain('geheim')
  })

  it('schickt das CSRF-Token bei schreibenden Anfragen mit', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(antwort(true, { name: 'A', role: 'admin' }))
    vi.stubGlobal('fetch', fetchMock)
    const auth = useAuthStore()

    await auth.login('muster', 'geheim')

    const optionen = fetchMock.mock.calls[0][1]
    expect(optionen.headers['X-XSRF-TOKEN']).toBe('test-token')
    expect(optionen.credentials).toBe('include')
  })

  it('meldet auch dann lokal ab, wenn der Server nicht antwortet', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const auth = useAuthStore()
    auth.user = { name: 'A. Muster', role: 'admin' }

    await auth.logout()

    expect(auth.isLoggedIn).toBe(false)
  })

  it('erkennt eine abgelaufene Sitzung an HTTP 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(antwort(false)))
    const auth = useAuthStore()

    await auth.loadCurrentUser()

    expect(auth.isLoggedIn).toBe(false)
    expect(auth.sessionChecked).toBe(true)
  })
})
