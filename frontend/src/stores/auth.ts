import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type Role = 'admin' | 'lehrkraft'

export interface AuthUser {
  name: string
  role: Role
}

export const useAuthStore = defineStore('auth', () => {
  // Platzhalter bis die echte Loginanbindung ans Backend steht.
  const user = ref<AuthUser | null>({ name: 'A. Muster', role: 'admin' })

  const isAdmin = computed(() => user.value?.role === 'admin')

  function logout() {
    user.value = null
  }

  return { user, isAdmin, logout }
})
