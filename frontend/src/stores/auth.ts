import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { getJson, postJson } from '@/api/client'

export type Role = 'admin' | 'lehrkraft'

export interface AuthUser {
  name: string
  role: Role
}

// Immer derselbe Text, egal was schiefging. Er darf nicht verraten, ob es den
// Benutzernamen ueberhaupt gibt.
const LOGIN_FAILED = 'Anmeldung fehlgeschlagen. Bitte Eingaben pruefen.'
const SERVER_UNREACHABLE =
  'Server nicht erreichbar. Bitte spaeter erneut versuchen.'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const errorMessage = ref('')
  const busy = ref(false)

  // Solange false, ist noch unbekannt ob eine Sitzung besteht.
  const sessionChecked = ref(false)

  const isLoggedIn = computed(() => user.value !== null)
  const isAdmin = computed(() => user.value?.role === 'admin')

  async function login(username: string, password: string): Promise<boolean> {
    busy.value = true
    errorMessage.value = ''

    try {
      const response = await postJson('/api/login', { username, password })

      if (!response.ok) {
        errorMessage.value = LOGIN_FAILED
        return false
      }

      user.value = (await response.json()) as AuthUser
      sessionChecked.value = true
      return true
    } catch {
      errorMessage.value = SERVER_UNREACHABLE
      return false
    } finally {
      busy.value = false
    }
  }

  async function logout() {
    try {
      await postJson('/api/logout', {})
    } catch {
      // Auch wenn der Server nicht antwortet, wird hier abgemeldet.
    }

    user.value = null
    sessionChecked.value = true
  }

  // Nach einem Neuladen der Seite ist der Store leer, das Sitzungscookie kann
  // aber noch gelten. Deshalb einmal beim Backend nachfragen.
  async function loadCurrentUser() {
    try {
      const response = await getJson('/api/me')
      user.value = response.ok ? ((await response.json()) as AuthUser) : null
    } catch {
      user.value = null
    } finally {
      sessionChecked.value = true
    }
  }

  return {
    user,
    errorMessage,
    busy,
    sessionChecked,
    isLoggedIn,
    isAdmin,
    login,
    logout,
    loadCurrentUser
  }
})
