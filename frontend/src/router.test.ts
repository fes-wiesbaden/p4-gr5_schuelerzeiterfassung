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

    await router.push({ name: 'raeume' })

    expect(router.currentRoute.value.name).toBe('live-anwesenheit')
  })

  it('lässt Administratoren administrative Routen direkt aufrufen', async () => {
    const auth = useAuthStore()
    auth.user = { name: 'A. Muster', role: 'admin' }

    await router.push({ name: 'raeume' })

    expect(router.currentRoute.value.name).toBe('raeume')
  })
})
