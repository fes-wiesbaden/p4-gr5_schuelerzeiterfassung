import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'

import { timetableDraft } from '@/api/timetableImport'
import { useAuthStore } from './auth'

afterEach(() => {
  timetableDraft.value = undefined
})

it.each(['Abmelden', 'Sitzungsablauf'])(
  'verwirft bei %s einen offenen Stundenplan-Entwurf',
  async (action) => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 204 }))
    )
    const auth = useAuthStore()
    auth.user = { name: 'Verwaltung', role: 'admin' }
    timetableDraft.value = {
      name: 'Plan',
      slots: [],
      notes: [],
      rooms: [],
      teachers: [],
      classCode: null,
      classId: null,
      classes: [],
      blockPlans: []
    }

    await nextTick()
    expect(sessionStorage.getItem('stundenplan-entwurf')).not.toBeNull()

    if (action === 'Abmelden') expect(await auth.logout()).toBe(true)
    else auth.clearSession()

    expect(timetableDraft.value).toBeUndefined()
    await nextTick()
    expect(sessionStorage.getItem('stundenplan-entwurf')).toBeNull()
  }
)
