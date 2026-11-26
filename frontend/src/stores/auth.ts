import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { getJson, postJson } from '@/api/client'

export type Role = 'admin' | 'lehrkraft'

export interface AuthUser {
  name: string
  role: Role
}

// Abgelehnte Anmeldungen erhalten denselben Text, damit kein Rückschluss auf
// vorhandene Benutzernamen möglich ist. Verbindungsfehler haben einen eigenen Hinweis
const LOGIN_FAILED = 'Anmeldung fehlgeschlagen. Bitte Eingaben prüfen.'
const SERVER_UNREACHABLE =
  'Server nicht erreichbar. Bitte später erneut versuchen.'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const errorMessage = ref('')
  const busy = ref(false)

  // false bedeutet: Der Anmeldestatus muss vor dem nächsten Seitenwechsel geladen werden
  const sessionChecked = ref(false)

  const isLoggedIn = computed(() => user.value !== null)
  const isAdmin = computed(() => user.value?.role === 'admin')

  async function login(username: string, password: string): Promise<boolean> {
    busy.value = true
    errorMessage.value = ''

    try {
      if (!sessionChecked.value) {
        await loadCurrentUser()
        if (!sessionChecked.value) {
          errorMessage.value = SERVER_UNREACHABLE
          return false
        }
      }
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

  function clearSession() {
    user.value = null
    sessionChecked.value = true
    errorMessage.value = ''
  }

  async function logout(): Promise<boolean> {
    busy.value = true
    errorMessage.value = ''
    try {
      const response = await postJson('/api/logout', {})
      if (!response.ok && response.status !== 401) {
        errorMessage.value = 'Abmeldung fehlgeschlagen. Bitte erneut versuchen.'
        return false
      }
      clearSession()
      // Nach der Abmeldung muss /api/me erneut aufgerufen werden, damit vor
      // der nächsten Anmeldung wieder ein CSRF-Token-Cookie vorhanden ist
      sessionChecked.value = false
      return true
    } catch {
      errorMessage.value =
        'Abmeldung nicht bestätigt: Server nicht erreichbar. Bitte erneut versuchen.'
      return false
    } finally {
      busy.value = false
    }
  }

  // Lädt Anzeigenamen und Rolle aus einer noch gültigen Cookiesitzung.
  // So wird der nach einem Neuladen leere Store wieder mit dem Backend abgeglichen
  async function loadCurrentUser() {
    try {
      const response = await getJson('/api/me')
      user.value = response.ok ? ((await response.json()) as AuthUser) : null
      sessionChecked.value = response.ok || response.status === 401
    } catch {
      user.value = null
      sessionChecked.value = false
    }
  }

  return {
    user,
    errorMessage,
    busy,
    sessionChecked,
    isLoggedIn,
    isAdmin,
    clearSession,
    login,
    logout,
    loadCurrentUser
  }
})
