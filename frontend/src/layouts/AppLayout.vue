<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'

import { useAuthStore } from '@/stores/auth'
import { administrationNavItems, mainNavItems } from './navigation'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const sidebarOpen = ref(false)

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

watch(
  () => route.fullPath,
  () => {
    sidebarOpen.value = false
  }
)

function handleLogout() {
  auth.logout()
  router.push({ name: 'live-anwesenheit' })
}

function handleSidebarKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    sidebarOpen.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="app-header__start">
        <button
          type="button"
          class="app-header__menu-toggle"
          aria-label="Navigation öffnen"
          :aria-expanded="sidebarOpen"
          aria-controls="app-sidebar"
          @click="sidebarOpen = !sidebarOpen"
        >
          <i class="pi pi-bars" aria-hidden="true" />
        </button>
        <span class="app-header__title">RFID-Anwesenheitserfassung</span>
      </div>
      <div class="app-header__user">
        <span class="app-header__user-info">
          {{ auth.user?.name }}
          <span class="app-header__user-role">· {{ roleLabel }}</span>
        </span>
        <Button label="Abmelden" size="small" outlined @click="handleLogout" />
      </div>
    </header>

    <div class="app-body">
      <div
        v-if="sidebarOpen"
        class="app-sidebar__backdrop"
        @click="sidebarOpen = false"
      />

      <aside
        id="app-sidebar"
        class="app-sidebar"
        :class="{ 'app-sidebar--open': sidebarOpen }"
        @keydown="handleSidebarKeydown"
      >
        <nav aria-label="Hauptnavigation">
          <ul class="app-nav">
            <li v-for="item in mainNavItems" :key="item.routeName">
              <RouterLink
                :to="{ name: item.routeName }"
                class="app-nav__link"
                active-class="app-nav__link--active"
              >
                <i class="pi" :class="item.icon" aria-hidden="true" />
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
                  <i class="pi" :class="item.icon" aria-hidden="true" />
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

.app-header__start {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-header__menu-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid #d7dce3;
  border-radius: 6px;
  background: #ffffff;
  color: #1e3a5c;
  font-size: 1rem;
  cursor: pointer;
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

.app-sidebar__backdrop {
  display: none;
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

.app-nav__link:focus-visible,
.app-header__menu-toggle:focus-visible {
  outline: 2px solid #4f8ef7;
  outline-offset: -2px;
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
  min-width: 0;
}

@media (max-width: 960px) {
  .app-header__menu-toggle {
    display: inline-flex;
  }

  .app-body {
    grid-template-columns: 1fr;
  }

  .app-sidebar {
    position: fixed;
    inset: 64px 0 0 0;
    width: 260px;
    height: calc(100vh - 64px);
    transform: translateX(-100%);
    visibility: hidden;
    transition:
      transform 0.2s ease,
      visibility 0s linear 0.2s;
    z-index: 20;
    overflow-y: auto;
  }

  .app-sidebar--open {
    transform: translateX(0);
    visibility: visible;
    transition: transform 0.2s ease;
  }

  .app-sidebar__backdrop {
    display: block;
    position: fixed;
    inset: 64px 0 0 0;
    background: rgba(15, 23, 42, 0.4);
    z-index: 10;
  }
}
</style>
