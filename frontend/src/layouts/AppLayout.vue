<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'

import { useAuthStore } from '@/stores/auth'
import { administrationNavItems, mainNavItems } from './navigation'

const auth = useAuthStore()
const router = useRouter()

const visibleAdministrationItems = computed(() =>
  auth.isAdmin ? administrationNavItems : []
)

const roleLabel = computed(() => {
  switch (auth.user?.role) {
    case 'admin':
      return 'Administrator'
    case 'lehrkraft':
      return 'Lehrkraft'
    default:
      return ''
  }
})

function handleLogout() {
  auth.logout()
  router.push({ name: 'live-anwesenheit' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <span class="app-header__title">RFID-Anwesenheitserfassung</span>
      <div class="app-header__user">
        <span class="app-header__user-info">
          {{ auth.user?.name }}
          <span class="app-header__user-role">· {{ roleLabel }}</span>
        </span>
        <Button label="Abmelden" size="small" outlined @click="handleLogout" />
      </div>
    </header>

    <div class="app-body">
      <aside class="app-sidebar">
        <nav>
          <ul class="app-nav">
            <li v-for="item in mainNavItems" :key="item.routeName">
              <RouterLink
                :to="{ name: item.routeName }"
                class="app-nav__link"
                active-class="app-nav__link--active"
              >
                <i class="pi" :class="item.icon" />
                <span>{{ item.label }}</span>
              </RouterLink>
            </li>
          </ul>

          <template v-if="visibleAdministrationItems.length">
            <p class="app-nav__section-label">Administration</p>
            <ul class="app-nav">
              <li
                v-for="item in visibleAdministrationItems"
                :key="item.routeName"
              >
                <RouterLink
                  :to="{ name: item.routeName }"
                  class="app-nav__link"
                  active-class="app-nav__link--active"
                >
                  <i class="pi" :class="item.icon" />
                  <span>{{ item.label }}</span>
                </RouterLink>
              </li>
            </ul>
          </template>
        </nav>
      </aside>

      <main class="app-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: grid;
  grid-template-rows: auto 1fr;
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 24px;
  background: #ffffff;
  border-bottom: 1px solid #e2e5ea;
}

.app-header__title {
  font-weight: 700;
  font-size: 1.05rem;
  color: #1e3a5c;
}

.app-header__user {
  display: flex;
  align-items: center;
  gap: 16px;
}

.app-header__user-info {
  font-size: 0.9rem;
  color: #2b3648;
}

.app-header__user-role {
  color: #6b7688;
}

.app-body {
  display: grid;
  grid-template-columns: 220px 1fr;
  min-height: 0;
}

.app-sidebar {
  background: #1e3a5c;
  padding: 16px 0;
}

.app-nav {
  list-style: none;
  margin: 0;
  padding: 0;
}

.app-nav__link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 20px;
  color: #c9d4e3;
  text-decoration: none;
  font-size: 0.9rem;
  border-left: 3px solid transparent;
}

.app-nav__link:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #ffffff;
}

.app-nav__link--active {
  background: rgba(255, 255, 255, 0.1);
  border-left-color: #4f8ef7;
  color: #ffffff;
  font-weight: 600;
}

.app-nav__section-label {
  margin: 20px 0 4px;
  padding: 0 20px;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #7c8aa3;
}

.app-content {
  padding: 32px;
  background: #f5f6f8;
}
</style>
